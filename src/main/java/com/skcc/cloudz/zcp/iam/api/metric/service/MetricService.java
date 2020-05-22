package com.skcc.cloudz.zcp.iam.api.metric.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.skcc.cloudz.zcp.iam.api.metric.vo.ClusterStatusMetricsVO;
import com.skcc.cloudz.zcp.iam.api.metric.vo.DeploymentsStatusMetricsVO;
import com.skcc.cloudz.zcp.iam.api.metric.vo.NodesStatusMetricsVO;
import com.skcc.cloudz.zcp.iam.api.metric.vo.PodsStatusMetricsVO;
import com.skcc.cloudz.zcp.iam.api.metric.vo.UsersStatusMetricsVO;
import com.skcc.cloudz.zcp.iam.common.exception.KeyCloakException;
import com.skcc.cloudz.zcp.iam.common.exception.ZcpErrorCode;
import com.skcc.cloudz.zcp.iam.common.exception.ZcpException;
import com.skcc.cloudz.zcp.iam.common.model.ClusterRole;
import com.skcc.cloudz.zcp.iam.common.model.DeploymentStatus;
import com.skcc.cloudz.zcp.iam.common.model.DeploymentStatusMetric;
import com.skcc.cloudz.zcp.iam.common.model.NodeStatus;
import com.skcc.cloudz.zcp.iam.common.model.NodeStatusMetric;
import com.skcc.cloudz.zcp.iam.common.model.PodStatus;
import com.skcc.cloudz.zcp.iam.common.model.PodStatusMetric;
import com.skcc.cloudz.zcp.iam.common.model.UserStatusMetric;
import com.skcc.cloudz.zcp.iam.common.model.V1alpha1NodeMetric;
import com.skcc.cloudz.zcp.iam.common.model.V1alpha1NodeMetricList;
import com.skcc.cloudz.zcp.iam.common.model.ZcpNamespace;
import com.skcc.cloudz.zcp.iam.common.model.ZcpNamespace.NamespaceStatus;
import com.skcc.cloudz.zcp.iam.common.model.ZcpNamespaceList;
import com.skcc.cloudz.zcp.iam.common.model.ZcpNode;
import com.skcc.cloudz.zcp.iam.common.model.ZcpNodeList;
import com.skcc.cloudz.zcp.iam.common.model.ZcpUser;
import com.skcc.cloudz.zcp.iam.common.util.NumberUtils;
import com.skcc.cloudz.zcp.iam.manager.KeyCloakManager;
import com.skcc.cloudz.zcp.iam.manager.KubeAppsManager;
import com.skcc.cloudz.zcp.iam.manager.KubeCoreManager;
import com.skcc.cloudz.zcp.iam.manager.KubeMetricManager;
import com.skcc.cloudz.zcp.iam.manager.KubeRbacAuthzManager;
import com.skcc.cloudz.zcp.iam.manager.ResourcesLabelManager;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.models.V1ClusterRoleBinding;
import io.kubernetes.client.models.V1Container;
import io.kubernetes.client.models.V1Deployment;
import io.kubernetes.client.models.V1DeploymentCondition;
import io.kubernetes.client.models.V1DeploymentList;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import io.kubernetes.client.models.V1Node;
import io.kubernetes.client.models.V1NodeCondition;
import io.kubernetes.client.models.V1NodeList;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1ResourceQuota;
import io.kubernetes.client.models.V1ResourceQuotaList;
import io.kubernetes.client.models.V1ResourceQuotaStatus;
import io.kubernetes.client.models.V1RoleBinding;
import io.kubernetes.client.models.V1RoleBindingList;

@Service
public class MetricService {

	private final Logger logger = LoggerFactory.getLogger(MetricService.class);

	@Autowired
	private KeyCloakManager keyCloakManager;

	@Autowired
	private KubeMetricManager kubeMetircManager;

	@Autowired
	private KubeCoreManager kubeCoreManager;

	@Autowired
	private KubeRbacAuthzManager kubeRbacAuthzManager;

	@Autowired
	private KubeAppsManager kubeAppsManager;

	public ZcpNodeList getNodes() throws ZcpException {
		V1NodeList nodeList = null;

		try {
			nodeList = kubeCoreManager.getNodeList();
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.NODE_LIST_ERROR, e);
		}

		List<V1Node> nodes = nodeList.getItems();
		List<ZcpNode> zcpNodes = new ArrayList<>();
		for (V1Node node : nodes) {
			String nodeName = node.getMetadata().getName();
			Map<String, String> labels = node.getMetadata().getLabels();
			Map<String, Quantity> allocatable = node.getStatus().getAllocatable();
			BigDecimal allocatableCpu = allocatable.get("cpu").getNumber();
			BigDecimal allocatableMem = allocatable.get("memory").getNumber();
			String roles = labels.keySet().stream()
								.filter(k -> k.startsWith("node-role.kubernetes.io/"))
								.map(k -> k.split("/")[1])
								.collect(Collectors.joining(","));

			// Map<String, Quantity> capacity = node.getStatus().getCapacity();
			
//			logger.debug("Node name is {}", nodeName);
//			logger.debug("Node role is {}", labels.get("role"));
//			logger.debug("allocatable memory is {}", allocatableCpu);
//			logger.debug("allocatable cpu is {}", ((Quantity) allocatable.get("memory")).getNumber());

			V1PodList pods = getPodListByNode(nodeName);

			double totalCpuRequests = 0;
			double totalMemRequests = 0;
			double totalCpuLimits = 0;
			double totalMemLimits = 0;

			StringBuffer debugLog = new StringBuffer();
			String podLogFormat = "    %-20s %-40s  %-28s %-10s %-10s %-10s %-10s%n";
			if (logger.isDebugEnabled()) {
			    debugLog.append("\n-----------------------------------------\n");
			    debugLog.append(String.format("Node Resource Status for %s (%s)\n", nodeName, roles));
			    debugLog.append(String.format("  Node Allocatable - CPU : %s, MEM : %s\n", allocatableCpu, allocatableMem));
			    debugLog.append("  Pod container resources\n");
			    debugLog.append(String.format(podLogFormat, "Namespace", "Pod", "Container", "CPU Req", "CPU Limit", "Mem Req", "Mem Limit"));
			    debugLog.append(String.format(podLogFormat, "---------", "---", "---------", "-------", "---------", "-------", "---------"));
			}
			
			for (V1Pod pod : pods.getItems()) {
				List<V1Container> containers = pod.getSpec().getContainers();
				for (V1Container container : containers) {
					Map<String, Quantity> requests = container.getResources().getRequests();
					Quantity cpuRequests = null, 
					        memoryRequests = null, 
					        cpulimits = null, 
					        memorylimits = null;
					if (requests != null) {
					    cpuRequests = requests.get("cpu");
						if (cpuRequests != null) {
//							logger.debug("cpu request is {}", cpuRequests.toSuffixedString());
							totalCpuRequests += cpuRequests.getNumber().doubleValue();
						}
						memoryRequests = requests.get("memory");
						if (memoryRequests != null) {
//							logger.debug("memory request is {}", memoryRequests.toSuffixedString());
							totalMemRequests += memoryRequests.getNumber().doubleValue();
						}
					}

					Map<String, Quantity> limits = container.getResources().getLimits();
					if (limits != null) {
						cpulimits = limits.get("cpu");
						if (cpulimits != null) {
//							logger.debug("cpu limits is {}", cpulimits.toSuffixedString());
							totalCpuLimits += cpulimits.getNumber().doubleValue();
						}
						memorylimits = limits.get("memory");
						if (memorylimits != null) {
//							logger.debug("memory limits is {}", memorylimits.toSuffixedString());
							totalMemLimits += memorylimits.getNumber().doubleValue();
						}
					}
					if (logger.isDebugEnabled()) {
					    debugLog.append(String.format(podLogFormat, 
					            pod.getMetadata().getNamespace(), 
					            pod.getMetadata().getName(),
					            container.getName(),
					            quantityToString(cpuRequests), 
					            quantityToString(cpulimits), 
					            quantityToString(memoryRequests), 
					            quantityToString(memorylimits)));
					}
				}
			}
			
			if (logger.isDebugEnabled()) {
			    String totalLogFormat = "    %-12s %-20s %-20s%n";
			    debugLog.append("  Total allocated resources\n");
			    debugLog.append(String.format(totalLogFormat, "Resource", "Requests", "Limits"));
			    debugLog.append(String.format(totalLogFormat, "--------", "--------", "------"));
			    
			    debugLog.append(String.format(totalLogFormat, "CPU", 
			            String.format("%.2f m", totalCpuRequests * 1000) + " (" + NumberUtils.percent(totalCpuRequests, allocatableCpu.doubleValue()) + "%)",
			            String.format("%.2f m", totalCpuLimits * 1000) + " (" + NumberUtils.percent(totalCpuLimits, allocatableCpu.doubleValue()) + "%)"));
			    debugLog.append(String.format(totalLogFormat, "Memory", 
			            String.format("%.2f Mi", totalMemRequests / 1024 / 1024) + " (" + NumberUtils.percent(totalMemRequests, allocatableMem.doubleValue()) + "%)",
			            String.format("%.2f Mi", totalMemLimits / 1024 / 1024) + " (" + NumberUtils.percent(totalMemLimits, allocatableMem.doubleValue()) + "%)"));
			    debugLog.append("-----------------------------------------");
			    logger.debug(debugLog.toString());
			}

//			logger.debug("total cpu requests is {}", totalCpuRequests);
//			logger.debug("total mem requests is {}", totalMemRequests);
//			logger.debug("total cpu limits is {}", totalCpuLimits);
//			logger.debug("total mem limits is {}", totalMemLimits);
//
//			logger.debug("CPU Requests = {}, Mem Requests = {}, CPU limits = {}, Mem limits = {}",
//					NumberUtils.percent(totalCpuRequests, allocatableCpu.doubleValue()),
//					NumberUtils.percent(totalMemRequests, allocatableMem.doubleValue()),
//					NumberUtils.percent(totalCpuLimits, allocatableCpu.doubleValue()),
//					NumberUtils.percent(totalMemLimits, allocatableMem.doubleValue()));
//
//			logger.debug("------------------------------------------------");

			ZcpNode zcpNode = new ZcpNode();
			zcpNode.setNodeName(nodeName);
			zcpNode.setNodeType(labels.get("ibm-cloud.kubernetes.io/machine-type"));
			zcpNode.setNodeRoles(roles);
			setNodeStatus(node, zcpNode);
			zcpNode.setCreationTime(new Date(node.getMetadata().getCreationTimestamp().getMillis()));
			zcpNode.setAllocatableCpu(allocatableCpu);
			zcpNode.setAllocatableCpuString(NumberUtils.formatCpu(allocatableCpu.doubleValue()));
			zcpNode.setAllocatableMemory(allocatableMem);
			zcpNode.setAllocatableMemoryString(NumberUtils.formatMemory(allocatableMem.doubleValue()));

			zcpNode.setCpuLimits(BigDecimal.valueOf(totalCpuLimits));
			zcpNode.setCpuLimitsString(NumberUtils.formatCpu(totalCpuLimits));
			zcpNode.setCpuRequests(BigDecimal.valueOf(totalCpuRequests));
			zcpNode.setCpuRequestsString(NumberUtils.formatCpu(totalCpuRequests));
			zcpNode.setMemoryLimits(BigDecimal.valueOf(totalMemLimits));
			zcpNode.setMemoryLimitsString(NumberUtils.formatMemory(totalMemLimits));
			zcpNode.setMemoryRequests(BigDecimal.valueOf(totalMemRequests));
			zcpNode.setMemoryRequestsString(NumberUtils.formatMemory(totalMemRequests));
			zcpNode.setCpuLimitsPercentage(NumberUtils.percent(totalCpuLimits, allocatableCpu.doubleValue()));
			zcpNode.setCpuRequestsPercentage(NumberUtils.percent(totalCpuRequests, allocatableCpu.doubleValue()));
			zcpNode.setMemoryLimitsPercentage(NumberUtils.percent(totalMemLimits, allocatableMem.doubleValue()));
			zcpNode.setMemoryRequestsPercentage(NumberUtils.percent(totalMemRequests, allocatableMem.doubleValue()));

			zcpNodes.add(zcpNode);
		}

		return new ZcpNodeList(zcpNodes);
	}
	
	private String quantityToString(Quantity quantity) {
	    if (quantity != null) {
	        return quantity.toSuffixedString();
	    }
	    
	    return "0";
	}
	
	public ZcpNamespaceList getNamespaces(String userId) throws ZcpException {
		// check user
		UserRepresentation userRepresentation = null;
		try {
			userRepresentation = keyCloakManager.getUser(userId);
		} catch (KeyCloakException e) {
			throw new ZcpException(ZcpErrorCode.USER_NOT_FOUND, "The user(" + userId + ") does not exist");
		}

		String username = userRepresentation.getUsername();
		logger.debug("keyclock username is - {}", username);

		// check clusterrolebinding
		V1ClusterRoleBinding userClusterRoleBinding = null;
		try {
			userClusterRoleBinding = kubeRbacAuthzManager.getClusterRoleBindingByUsername(username);
		} catch (ApiException e2) {
			throw new ZcpException(ZcpErrorCode.CLUSTERROLEBINDING_NOT_FOUND, "The clusterrolebinding of user(" + userId + ") does not exist");
		}

		String userClusterRole = userClusterRoleBinding.getRoleRef().getName();
		boolean isClusterAdmin = StringUtils.equals(userClusterRole, ClusterRole.CLUSTER_ADMIN.getRole()) ? true
				: false;

		// get namespace list of admin user
		List<String> userNamespaces = new ArrayList<>();
		List<String> adminRoles = new ArrayList<>();
		adminRoles.add(ClusterRole.ADMIN.getRole());
		adminRoles.add(ClusterRole.CICD_MANAGER.getRole());
		try {
			List<V1RoleBinding> userRoleBindings = null;
			if(!isClusterAdmin) {
				userRoleBindings = kubeRbacAuthzManager.getRoleBindingListByUsername(username).getItems();
			}
			if (userRoleBindings != null && !userRoleBindings.isEmpty()) {
				for (V1RoleBinding roleBinding : userRoleBindings) {
					if (adminRoles.contains(roleBinding.getRoleRef().getName())) {
						userNamespaces.add(roleBinding.getMetadata().getNamespace());
					}
				}
			}
		} catch (ApiException e1) {
			throw new ZcpException(ZcpErrorCode.ROLE_BINDING_LIST_ERROR, e1);
		}

		// get all namespace list
		V1NamespaceList v1NamespaceList = null;
		try {
			v1NamespaceList = kubeCoreManager.getNamespaceList();
		} catch (ApiException e) {
			logger.debug("There is no namespace");
			return new ZcpNamespaceList(new ArrayList<ZcpNamespace>());
		}

		V1ResourceQuotaList v1ResourceQuotaList = null;
		try {
			v1ResourceQuotaList = kubeCoreManager.getAllResourceQuotaList();
		} catch (ApiException e) {
			// we can ignore this case
			logger.debug("There is no resource quotas");
		}

		Map<String, V1ResourceQuota> mappedResourceQuotas = getMappedResoruceQuotas(v1ResourceQuotaList);
		Map<String, List<V1RoleBinding>> mappedRolebindins = getMappedRoleBindings();
		List<ZcpNamespace> zcpNamespaces = new ArrayList<>();

		for (V1Namespace namespace : v1NamespaceList.getItems()) {
			ZcpNamespace zcpNamespace = new ZcpNamespace();
			zcpNamespace.setName(namespace.getMetadata().getName());
			zcpNamespace.setCreationDate(new Date(namespace.getMetadata().getCreationTimestamp().getMillis()));
			zcpNamespace.setStatus(NamespaceStatus.getNamespaceStatus(namespace.getStatus().getPhase()));
			List<V1RoleBinding> namespacedRolebinds = mappedRolebindins.get(namespace.getMetadata().getName());
			if (namespacedRolebinds != null) {
				zcpNamespace.setUserCount(namespacedRolebinds.size());
			}

			V1ResourceQuota resourceQuota = mappedResourceQuotas.get(namespace.getMetadata().getName());
			if (resourceQuota != null) {
				V1ResourceQuotaStatus status = resourceQuota.getStatus();
				Map<String, String> hard = status.getHard();
				Map<String, String> used = status.getUsed();

				logger.debug("namespace is {}", namespace.getMetadata().getName());
				logger.debug("hard is {}", hard);
				logger.debug("used is {}", used);

				String sHardRequestsCpu = hard.get("requests.cpu") == null ? "0" : hard.get("requests.cpu");
				String sUsedRequestsCpu = used.get("requests.cpu") == null ? "0" : used.get("requests.cpu");
				String sHardLimitsCpu = hard.get("limits.cpu") == null ? "0" : hard.get("limits.cpu");
				String sUsedLimitsCpu = used.get("limits.cpu") == null ? "0" : used.get("limits.cpu");

				String sHardRequestsMemory = hard.get("requests.memory") == null ? "0" : hard.get("requests.memory");
				String sUsedRequestsMemory = used.get("requests.memory") == null ? "0" : used.get("requests.memory");
				String sHardLimitsMemory = hard.get("limits.memory") == null ? "0" : hard.get("limits.memory");
				String sUsedLimitsMemory = used.get("limits.memory") == null ? "0" : used.get("limits.memory");

				BigDecimal hardRequestsCpu = Quantity.fromString(sHardRequestsCpu).getNumber();
				BigDecimal usedRequestsCpu = Quantity.fromString(sUsedRequestsCpu).getNumber();
				zcpNamespace.setHardCpuRequests(hardRequestsCpu);
				zcpNamespace.setUsedCpuRequests(usedRequestsCpu);
				zcpNamespace.setHardCpuRequestsString(NumberUtils.formatCpu(hardRequestsCpu.doubleValue()));
				zcpNamespace.setUsedCpuRequestsString(NumberUtils.formatCpu(usedRequestsCpu.doubleValue()));
				zcpNamespace.setCpuRequestsPercentage(
						NumberUtils.percent(usedRequestsCpu.doubleValue(), hardRequestsCpu.doubleValue()));

				BigDecimal hardLimitsCpu = Quantity.fromString(sHardLimitsCpu).getNumber();
				BigDecimal usedLimitsCpu = Quantity.fromString(sUsedLimitsCpu).getNumber();
				zcpNamespace.setHardCpuLimits(hardLimitsCpu);
				zcpNamespace.setUsedCpuLimits(usedLimitsCpu);
				zcpNamespace.setHardCpuLimitsString(NumberUtils.formatCpu(hardLimitsCpu.doubleValue()));
				zcpNamespace.setUsedCpuLimitsString(NumberUtils.formatCpu(usedLimitsCpu.doubleValue()));
				zcpNamespace.setCpuLimitsPercentage(
						NumberUtils.percent(usedLimitsCpu.doubleValue(), hardLimitsCpu.doubleValue()));

				BigDecimal hardRequestsMemory = Quantity.fromString(sHardRequestsMemory).getNumber();
				BigDecimal usedRequestsMemory = Quantity.fromString(sUsedRequestsMemory).getNumber();
				zcpNamespace.setHardMemoryRequests(hardRequestsMemory);
				zcpNamespace.setUsedMemoryRequests(usedRequestsMemory);
				zcpNamespace.setHardMemoryRequestsString(NumberUtils.formatMemory(hardRequestsMemory.doubleValue()));
				zcpNamespace.setUsedMemoryRequestsString(NumberUtils.formatMemory(usedRequestsMemory.doubleValue()));
				zcpNamespace.setMemoryRequestsPercentage(
						NumberUtils.percent(usedRequestsMemory.doubleValue(), hardRequestsMemory.doubleValue()));

				BigDecimal hardLimitsMemory = Quantity.fromString(sHardLimitsMemory).getNumber();
				BigDecimal usedLimitsMemory = Quantity.fromString(sUsedLimitsMemory).getNumber();
				zcpNamespace.setHardMemoryLimits(hardLimitsMemory);
				zcpNamespace.setUsedMemoryLimits(usedLimitsMemory);
				zcpNamespace.setHardMemoryLimitsString(NumberUtils.formatMemory(hardLimitsMemory.doubleValue()));
				zcpNamespace.setUsedMemoryLimitsString(NumberUtils.formatMemory(usedLimitsMemory.doubleValue()));
				zcpNamespace.setMemoryLimitsPercentage(
						NumberUtils.percent(usedLimitsMemory.doubleValue(), hardLimitsMemory.doubleValue()));
			}

//			if (!isClusterAdmin && isAdmin) {
//				if (userNamespaces.contains(namespace.getMetadata().getName())) {
//					zcpNamespaces.add(zcpNamespace);
//				}
//			} else {
//				zcpNamespaces.add(zcpNamespace);
//			}
			
			if(isClusterAdmin || userNamespaces.contains(zcpNamespace.getName()))
				zcpNamespaces.add(zcpNamespace);
		}

		return new ZcpNamespaceList(zcpNamespaces);
	}

	public ClusterStatusMetricsVO getClusterMetrics(String type) throws ZcpException {
		if (StringUtils.isEmpty(type) || (!StringUtils.equals(type, "cpu") && !StringUtils.equals(type, "memory"))) {
			throw new ZcpException(ZcpErrorCode.UNSUPPORTED_TYPE, "Cluster Unsupported type(" + type + ")");
		}

		V1alpha1NodeMetricList nodeMetricList = null;
		try {
			nodeMetricList = kubeMetircManager.listNodeMetrics();
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.LIST_NODE_METRICS_ERROR, e);
		}

		BigDecimal utilization = calcluateUtilization(nodeMetricList, type);

		V1NodeList nodeList = null;

		try {
			nodeList = kubeCoreManager.getNodeList();
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.NODE_LIST_ERROR, e);
		}

		BigDecimal allocatable = calcluateAllocatable(nodeList, type);

		BigDecimal available = new BigDecimal(allocatable.doubleValue() - utilization.doubleValue());

		ClusterStatusMetricsVO vo = new ClusterStatusMetricsVO();
		if (StringUtils.equals(type, "cpu")) {
			vo.setTitle("CPU");
			vo.setUnit("Core");
			vo.setAvailable(NumberUtils.formatCpuWithoutUnit(available.doubleValue()));
			vo.setTotal(NumberUtils.formatCpuWithoutUnit(allocatable.doubleValue()));
			vo.setUtilization(NumberUtils.formatCpuWithoutUnit(utilization.doubleValue()));
			vo.setUtilizationPercentage(NumberUtils.percent(utilization.doubleValue(), allocatable.doubleValue()));
		} else {
			vo.setTitle("Memory");
			vo.setUnit("Gi");
			vo.setAvailable(NumberUtils.formatMemoryWithoutUnit(available.doubleValue()));
			vo.setTotal(NumberUtils.formatMemoryWithoutUnit(allocatable.doubleValue()));
			vo.setUtilization(NumberUtils.formatMemoryWithoutUnit(utilization.doubleValue()));
			vo.setUtilizationPercentage(NumberUtils.percent(utilization.doubleValue(), allocatable.doubleValue()));
		}
		vo.setUtilizationTitle("Utilization");

		return vo;
	}

	public DeploymentsStatusMetricsVO getDeploymentsStatusMetrics(String namespace) throws ZcpException {
		V1DeploymentList deploymentList = null;
		try {
			deploymentList = kubeAppsManager.getDeploymentList(namespace);
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.DEPLOYMENT_LIST_ERROR, e);
		}

		List<V1Deployment> deployments = deploymentList.getItems();
		Map<DeploymentStatus, DeploymentStatusMetric> statuesMetrics = getDeploymentsStatusMap();

		for (V1Deployment deployment : deployments) {
			List<V1DeploymentCondition> conditions = deployment.getStatus().getConditions();
			for (V1DeploymentCondition condition : conditions) {
				if (condition.getType().equals(DeploymentStatus.STATUS_CONDITION_TYPE)) {
					if (condition.getStatus().equals("True")) {
						DeploymentStatusMetric dsm = statuesMetrics.get(DeploymentStatus.Available);
						if (dsm != null) {
							dsm.increaseCount();
						} else {
							dsm = new DeploymentStatusMetric();
							dsm.setStatus(DeploymentStatus.Available);
							dsm.setCount(1);
							statuesMetrics.put(DeploymentStatus.Available, dsm);
						}
					} else {
						DeploymentStatusMetric dsm = statuesMetrics.get(DeploymentStatus.Unavailable);
						if (dsm != null) {
							dsm.increaseCount();
						} else {
							dsm = new DeploymentStatusMetric();
							dsm.setStatus(DeploymentStatus.Unavailable);
							dsm.setCount(1);
							statuesMetrics.put(DeploymentStatus.Unavailable, dsm);
						}
					}
				}
			}
		}

		DeploymentsStatusMetricsVO vo = new DeploymentsStatusMetricsVO();
		vo.setStatuses(statuesMetrics.values().stream().collect(Collectors.toList()));
		vo.setTotalCount(BigDecimal.valueOf(deployments.size()));

		return vo;
	}

	public NodesStatusMetricsVO getNodesStatusMetrics() throws ZcpException {
		V1NodeList nodeList = null;

		try {
			nodeList = kubeCoreManager.getNodeList();
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.NODE_LIST_ERROR, e);
		}

		List<V1Node> nodes = nodeList.getItems();
		Map<NodeStatus, NodeStatusMetric> statuesMetrics = getNodesStatusMap();

		for (V1Node node : nodes) {
			List<V1NodeCondition> conditions = node.getStatus().getConditions();
			for (V1NodeCondition condition : conditions) {
				if (condition.getType().equals(NodeStatus.STATUS_CONDITION_TYPE)) {
					if (condition.getStatus().equals("True")) {
						NodeStatusMetric nsm = statuesMetrics.get(NodeStatus.Ready);
						if (nsm != null) {
							nsm.increaseCount();
						} else {
							nsm = new NodeStatusMetric();
							nsm.setStatus(NodeStatus.Ready);
							nsm.setCount(1);
							statuesMetrics.put(NodeStatus.Ready, nsm);
						}
					} else if (condition.getStatus().equals("False")) {
						NodeStatusMetric nsm = statuesMetrics.get(NodeStatus.NotReady);
						if (nsm != null) {
							nsm.increaseCount();
						} else {
							nsm = new NodeStatusMetric();
							nsm.setStatus(NodeStatus.NotReady);
							nsm.setCount(1);
							statuesMetrics.put(NodeStatus.NotReady, nsm);
						}
					} else {
						NodeStatusMetric nsm = statuesMetrics.get(NodeStatus.Unknown);
						if (nsm != null) {
							nsm.increaseCount();
						} else {
							nsm = new NodeStatusMetric();
							nsm.setStatus(NodeStatus.Unknown);
							nsm.setCount(1);
							statuesMetrics.put(NodeStatus.Unknown, nsm);
						}
					}
				}
			}
		}

		NodesStatusMetricsVO vo = new NodesStatusMetricsVO();
		vo.setStatuses(statuesMetrics.values().stream().collect(Collectors.toList()));
		vo.setTotalCount(BigDecimal.valueOf(nodes.size()));

		return vo;
	}

	public PodsStatusMetricsVO getPodsStatusMetrics(String namespace) throws ZcpException {
		V1PodList podList = null;

		try {
			if (StringUtils.isEmpty(namespace)) {
				podList = kubeCoreManager.getAllPodList();
			} else {
				podList = kubeCoreManager.getPodListByNamespace(namespace);
			}
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.POD_LIST_ERROR, e);
		}

		List<V1Pod> pods = podList.getItems();
		Map<PodStatus, PodStatusMetric> statuesMetrics = getPodsStatusMap();

		for (V1Pod pod : pods) {
			String phase = pod.getStatus().getPhase();
			
			PodStatus status = PodStatus.findByPhase(phase);

			if (status != null) {
				PodStatusMetric psm = statuesMetrics.get(status);
				if (psm != null) {
					psm.increaseCount();
				} else {
					psm = new PodStatusMetric();
					psm.setStatus(status);
					psm.setCount(1);
					statuesMetrics.put(status, psm);
				}
			} else {
				logger.warn("This phase(" + phase + ") does not exist in PodStatus. Please check it");
			}
		}

		PodsStatusMetricsVO vo = new PodsStatusMetricsVO();
		vo.setStatuses(statuesMetrics.values().stream().collect(Collectors.toList()));
		vo.setTotalCount(BigDecimal.valueOf(pods.size()));

		return vo;
	}

	public UsersStatusMetricsVO getUsersStatusMetrics(String namespace) throws ZcpException {
		Map<ClusterRole, UserStatusMetric> statuesMetrics = null;

		if (StringUtils.isEmpty(namespace)) {
			try {
				statuesMetrics = getClusterRoleStatus();
			} catch (ApiException e) {
				throw new ZcpException(ZcpErrorCode.CLUSTER_ROLE_STATUS_ERROR, e);
			}
		} else {
			try {
				statuesMetrics = getNamespaceRoleStatus(namespace);
			} catch (ApiException e) {
				throw new ZcpException(ZcpErrorCode.NAMESPACE_ROLE_STATUS_ERROR, e);
			}
		}

		UsersStatusMetricsVO vo = new UsersStatusMetricsVO();
		vo.setRoles(statuesMetrics.values().stream().collect(Collectors.toList()));
		if (StringUtils.isEmpty(namespace)) {
			vo.setMainRole(ClusterRole.CLUSTER_ADMIN);
		} else {
			vo.setMainRole(ClusterRole.ADMIN);
		}
		int sum = 0;
		for (UserStatusMetric metric : vo.getRoles()) {
			sum += metric.getCount().intValue();
		}
		vo.setTotalCount(BigDecimal.valueOf(sum));

		return vo;
	}

	private BigDecimal calcluateAllocatable(V1NodeList nodeList, String type) {
		double allocatable = 0d;
		for (V1Node node : nodeList.getItems()) {
			double data = 0d;
			if (StringUtils.equals(type, "cpu")) {
				data = node.getStatus().getAllocatable().get("cpu").getNumber().doubleValue();
			} else {
				data = node.getStatus().getAllocatable().get("memory").getNumber().doubleValue();
			}
			allocatable += data;
			logger.debug("Allocatable ::: {} - {}'s value is {}, sum is {}", node.getMetadata().getName(), type, data,
					allocatable);
		}

		return new BigDecimal(allocatable);
	}

	private BigDecimal calcluateUtilization(V1alpha1NodeMetricList nodeMetricList, String type) {
		double utilization = 0d;
		for (V1alpha1NodeMetric nodeMetric : nodeMetricList.getItems()) {
			double data = 0d;
			if (StringUtils.equals(type, "cpu")) {
				data = nodeMetric.getUsage().getCpu().getNumber().doubleValue();
			} else {
				data = nodeMetric.getUsage().getMemory().getNumber().doubleValue();
			}
			utilization += data;
			logger.debug("Utilizaion ::: {} - {}'s value is {}, sum is {}", nodeMetric.getMetadata().getName(), type,
					data, utilization);
		}

		return new BigDecimal(utilization);
	}

	private Map<String, V1ResourceQuota> getMappedResoruceQuotas(V1ResourceQuotaList v1ResourceQuotaList) {
		Map<String, V1ResourceQuota> data = new HashMap<>();

		if (v1ResourceQuotaList == null) {
			return data;
		}

		List<V1ResourceQuota> resourceQuotas = v1ResourceQuotaList.getItems();
		if (resourceQuotas == null) {
			return data;
		}

		for (V1ResourceQuota quota : resourceQuotas) {
			// if namespace has quotas more than 1, the last quota is set
			data.put(quota.getMetadata().getNamespace(), quota);
		}

		return data;
	}

	private Map<String, List<V1RoleBinding>> getMappedRoleBindings() throws ZcpException {
		List<V1RoleBinding> roleBindings = null;
		try {
			roleBindings = kubeRbacAuthzManager.getRoleBindingListAllNamespaces().getItems();
		} catch (ApiException e) {
			// we can ignore this case
			logger.debug("There is no resource quotas");
		}

		Map<String, List<V1RoleBinding>> data = new HashMap<>();
		if (roleBindings != null) {
			for (V1RoleBinding roleBinding : roleBindings) {
				String namespace = roleBinding.getMetadata().getNamespace();
				List<V1RoleBinding> namespaceRoleBindings = data.get(namespace);
				if (namespaceRoleBindings == null) {
					namespaceRoleBindings = new ArrayList<>();
					namespaceRoleBindings.add(roleBinding);
					data.put(namespace, namespaceRoleBindings);
				} else {
					namespaceRoleBindings.add(roleBinding);
				}
			}
		}

		return data;
	}

	private void setNodeStatus(V1Node node, ZcpNode zcpNode) {
		List<V1NodeCondition> conditions = node.getStatus().getConditions();
		if (conditions == null)
			return;

		for (V1NodeCondition condition : conditions) {
			if (StringUtils.equals(condition.getType(), "Ready")) {
				if (StringUtils.equals(condition.getStatus(), "True")) {
					zcpNode.setStatus(NodeStatus.Ready);
				} else if (StringUtils.equals(condition.getStatus(), "False")) {
					zcpNode.setStatus(NodeStatus.NotReady);
				} else if (StringUtils.equals(condition.getStatus(), "Unknown")) {
					zcpNode.setStatus(NodeStatus.Unknown);
				}
			}
		}
	}

	private V1PodList getPodListByNode(String nodeName) throws ZcpException {
		V1PodList podList = null;

		try {
			podList = kubeCoreManager.getPodListByNode(nodeName);
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.POD_LIST_ERROR, e);
		}

		return podList;
	}

	private Map<DeploymentStatus, DeploymentStatusMetric> getDeploymentsStatusMap() {
		Map<DeploymentStatus, DeploymentStatusMetric> statuesMetrics = new HashMap<>();

		for (DeploymentStatus status : DeploymentStatus.values()) {
			DeploymentStatusMetric psm = new DeploymentStatusMetric();
			psm.setStatus(status);
			psm.setCount(0);
			statuesMetrics.put(status, psm);
		}

		return statuesMetrics;
	}

	private Map<NodeStatus, NodeStatusMetric> getNodesStatusMap() {
		Map<NodeStatus, NodeStatusMetric> statuesMetrics = new HashMap<>();

		for (NodeStatus status : NodeStatus.values()) {
			NodeStatusMetric psm = new NodeStatusMetric();
			psm.setStatus(status);
			psm.setCount(0);
			statuesMetrics.put(status, psm);
		}

		return statuesMetrics;
	}

	private Map<PodStatus, PodStatusMetric> getPodsStatusMap() {
		Map<PodStatus, PodStatusMetric> statuesMetrics = new HashMap<>();

		for (PodStatus status : PodStatus.values()) {
			PodStatusMetric psm = new PodStatusMetric();
			psm.setStatus(status);
			psm.setCount(0);
			statuesMetrics.put(status, psm);
		}

		return statuesMetrics;
	}

	private Map<ClusterRole, UserStatusMetric> getClusterRoleStatus() throws ApiException {
		List<UserRepresentation> keyCloakUsers = keyCloakManager.getUserList(null);

		List<ZcpUser> users = new ArrayList<ZcpUser>();
		for (UserRepresentation cloakUser : keyCloakUsers) {
			ZcpUser user = new ZcpUser();
			user.setId(cloakUser.getId());
			user.setUsername(cloakUser.getUsername());
			user.setEmail(cloakUser.getEmail());
			user.setLastName(cloakUser.getLastName());
			user.setFirstName(cloakUser.getFirstName());
			user.setCreatedDate(new Date(cloakUser.getCreatedTimestamp()));
			user.setEnabled(cloakUser.isEnabled());

			users.add(user);
		}

		Map<String, V1ClusterRoleBinding> mappedClusterRoleBindings = getMappedClusterRoleBindings();

		for (ZcpUser user : users) {
			V1ClusterRoleBinding userClusterRoleBinding = mappedClusterRoleBindings.get(user.getUsername());
			if (userClusterRoleBinding != null) {
				user.setClusterRole(ClusterRole.getClusterRole(userClusterRoleBinding.getRoleRef().getName()));
			} else {
				user.setClusterRole(ClusterRole.NONE);
			}
		}

		Map<ClusterRole, UserStatusMetric> data = getUserStatusMap();
		for (ZcpUser user : users) {
			ClusterRole role = user.getClusterRole();
			UserStatusMetric usm = data.get(role);

			if (usm != null) {
				usm.increaseCount();
			} else {
				usm = new UserStatusMetric();
				usm.setRole(role);
				usm.setCount(1);
				data.put(role, usm);
			}
		}

		return data;
	}

	private Map<ClusterRole, UserStatusMetric> getNamespaceRoleStatus(String namespace) throws ApiException {
		V1RoleBindingList v1RoleBindingList = kubeRbacAuthzManager.getRoleBindingListByNamespace(namespace);

		Map<ClusterRole, UserStatusMetric> data = getUserStatusMap();
		for (V1RoleBinding v1RoleBinding : v1RoleBindingList.getItems()) {
			ClusterRole role = ClusterRole.getClusterRole(v1RoleBinding.getRoleRef().getName());
			UserStatusMetric usm = data.get(role);

			if (usm != null) {
				usm.increaseCount();
			} else {
				usm = new UserStatusMetric();
				usm.setRole(role);
				usm.setCount(1);
				data.put(role, usm);
			}
		}

		return data;
	}

	private Map<String, V1ClusterRoleBinding> getMappedClusterRoleBindings() throws ApiException {
		List<V1ClusterRoleBinding> clusterRoleBindings = kubeRbacAuthzManager.getClusterRoleBindingList().getItems();

		Map<String, V1ClusterRoleBinding> map = new HashMap<>();
		for (V1ClusterRoleBinding clusterRoleBinding : clusterRoleBindings) {
			String username = clusterRoleBinding.getMetadata().getLabels()
					.get(ResourcesLabelManager.SYSTEM_USERNAME_LABEL_NAME);
			map.put(username, clusterRoleBinding);
		}

		return map;
	}

	private Map<ClusterRole, UserStatusMetric> getUserStatusMap() {
		Map<ClusterRole, UserStatusMetric> statuesMetrics = new HashMap<>();

		for (ClusterRole role : ClusterRole.getMetricGroup()) {
			UserStatusMetric usm = new UserStatusMetric();
			usm.setRole(role);
			usm.setCount(0);
			statuesMetrics.put(role, usm);
		}

		return statuesMetrics;
	}

}
