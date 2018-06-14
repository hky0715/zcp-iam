package com.skcc.cloudz.zcp.namespace.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.DateTime;
import org.json.simple.parser.ParseException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skcc.cloudz.zcp.common.exception.ZcpException;
import com.skcc.cloudz.zcp.manager.KeyCloakManager;
import com.skcc.cloudz.zcp.manager.KubeCoreManager;
import com.skcc.cloudz.zcp.manager.KubeRbacAuthzManager;
import com.skcc.cloudz.zcp.manager.ResourcesLabelManager;
import com.skcc.cloudz.zcp.manager.ResourcesNameManager;
import com.skcc.cloudz.zcp.namespace.vo.KubeDeleteOptionsVO;
import com.skcc.cloudz.zcp.namespace.vo.NamespaceVO;
import com.skcc.cloudz.zcp.namespace.vo.QuotaList;
import com.skcc.cloudz.zcp.namespace.vo.QuotaVO;
import com.skcc.cloudz.zcp.namespace.vo.RoleBindingVO;
import com.skcc.cloudz.zcp.user.vo.ServiceAccountVO;

import ch.qos.logback.classic.Logger;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1ClusterRoleBinding;
import io.kubernetes.client.models.V1LimitRange;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import io.kubernetes.client.models.V1NamespaceSpec;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1ResourceQuota;
import io.kubernetes.client.models.V1ResourceQuotaList;
import io.kubernetes.client.models.V1RoleBindingList;
import io.kubernetes.client.models.V1RoleRef;
import io.kubernetes.client.models.V1Subject;

@Service
public class NamespaceService {

	private final Logger LOG = (Logger) LoggerFactory.getLogger(NamespaceService.class);
	
	@Autowired
	private KeyCloakManager keyCloakManager;
	
	@Autowired
	private KubeCoreManager kubeCoreManager;
	
	@Autowired
	private KubeRbacAuthzManager kubeRbacAuthzManager;
	
	@Value("${zcp.kube.namespace}")
	private String systemNamespace;
	
	
	public V1Namespace getNamespace(String namespace) throws ApiException, ParseException{
		try {
			return kubeCoreManager.getNamespace(namespace);
		}catch(ApiException e) {
			if(!e.getMessage().equals("Not Found")){
				throw e;
			}
		}
		return null;
	}
	
	public V1NamespaceList getNamespaceList() throws ApiException, ParseException{
		try {
			return kubeCoreManager.getNamespaceList();
		}catch(ApiException e) {
			if(!e.getMessage().equals("Not Found")){
				throw e;
			}
		}
		return null;
	}
	
	public NamespaceVO getNamespaceResource(String namespace) throws ApiException, ParseException{
		NamespaceVO vo = new NamespaceVO();
		V1ResourceQuota quota =  kubeCoreManager.getResourceQuota(namespace, namespace);
		V1LimitRange limitRanges =  kubeCoreManager.getLimitRange(namespace, namespace);
		vo.setLimitRange(limitRanges);
		vo.setResourceQuota(quota);
		
		return vo;
		
	}
	
	@SuppressWarnings("unchecked")
	public QuotaList getResourceQuota() throws ApiException, ParseException{
		V1ResourceQuotaList quota = kubeCoreManager.getAllResourceQuotaList();
		List<QuotaVO> listQuota = new ArrayList<>();
		for(V1ResourceQuota q : quota.getItems()) {
			QuotaVO vo = new QuotaVO();
			Object[] obj = getInfoOfNamespace(q.getMetadata().getNamespace());
			vo.setName(q.getMetadata().getName());
			vo.setNamespace(q.getMetadata().getNamespace());
			vo.setUserCount(getNamespaceUserCount(q.getMetadata().getNamespace()));
			//vo.setSpec(q.getSpec());
			vo.setActive((String)obj[0]);
			vo.setLabels((List<String>)obj[1]);
			vo.setStatus(q.getStatus());
			vo.setUsedCpuRate(getUsedCpuRate(q.getStatus().getUsed().get("limits.cpu")
					, q.getStatus().getHard().get("limits.cpu")));
			vo.setUsedMemoryRate(getUsedMemoryRate(q.getStatus().getUsed().get("limits.memory")
					, q.getStatus().getHard().get("limits.memory")));
			vo.setCreationTimestamp(new DateTime(q.getMetadata().getCreationTimestamp()));
			listQuota.add(vo);
		}
		QuotaList list = new QuotaList();
		list.setItems(listQuota);
		return  list;
	}
	
	public Object[] getInfoOfNamespace(String namespaceName) throws ApiException, ParseException {
		List<String> labels = new ArrayList<>();
		V1Namespace namespace = this.getNamespace(namespaceName);
		String active = namespace.getStatus().getPhase().equals("Active") ? "active" : "inactive";
		Map<String, String> label = namespace.getMetadata().getLabels();
		if(label != null)
			for(String key : label.keySet()) {
				String strLabel = key + ":" + label.get(key);
				labels.add(strLabel);
			}
		Object[] obj = {active, labels};
		
		return obj;
	}
	
	private double getUsedMemoryRate(String used, String hard) {
		int iUsed=0;
		int iHard=0;
		if(used != null)
			if(used.indexOf("Gi") > -1) {
				iUsed = Integer.parseInt(used.replace("Gi", ""));
			}else { 
				iUsed = Integer.parseInt(used.replace("Gi", ""));
				iUsed *= 1000;
			}
		
		if(hard != null)
			if(hard.indexOf("Gi") > -1) {
				iHard = Integer.parseInt(hard.replace("Gi", ""));
			}else { 
				iHard = Integer.parseInt(hard.replace("Gi", ""));
				iHard *= 1000;
			}
		
		return iHard == 0 ? 0 : Math.round((iUsed/iHard*100)/100.0);
	}
	
	private double getUsedCpuRate(String used, String hard) {
		int iUsed=0;
		int iHard=0;
		if(used != null)
			if(used.indexOf("m") > -1) {
				iUsed = Integer.parseInt(used.replace("m", ""));
			}else { 
				iUsed = Integer.parseInt(used.replace("m", ""));
				iUsed *= 1000;
			}
		if(hard != null)
			if(hard.indexOf("m") > -1) {
				iHard = Integer.parseInt(hard.replace("m", ""));
			}else { 
				iHard = Integer.parseInt(hard.replace("m", ""));
				iHard *= 1000;
			}
		
		return iHard == 0 ? 0 : Math.round((iUsed/iHard*100)/100.0);
	}
	
	private int getNamespaceUserCount(String namespaceName) throws ApiException {
		V1RoleBindingList list = kubeRbacAuthzManager.getRoleBindingListByNamespace(namespaceName);
		return list.getItems().size();
	}
	
	
	
	
	
	/**
	 * only name of namespaces
	 * @param namespace
	 * @return
	 * @throws ApiException
	 * @throws ParseException
	 */
	@SuppressWarnings(value= {"unchecked", "rawtypes"})
	@Deprecated
	public List<Map> getAllOfNamespace() throws ApiException, ParseException{
		List<Map> namespaceList = new ArrayList();
		V1NamespaceList map =  kubeCoreManager.getNamespaceList();
		List<V1Namespace> item = (List<V1Namespace>) map.getItems();
		item.stream().forEach((data) ->{
			String name = data.getMetadata().getName();
			Map<String, String> mapNamespace = new HashMap();
			mapNamespace.put("name", name);
			namespaceList.add(mapNamespace);
		});
		return namespaceList;
	}
	
	
	/**
	 * 네임스페이스 생성 또는 변경
	 * @param namespacevo
	 * @param quotavo
	 * @param limitvo
	 * @throws ApiException
	 */
	public void createAndEditNamespace(NamespaceVO data) throws ApiException {
		V1ObjectMeta namespace_meta = new V1ObjectMeta();
		V1ObjectMeta quota_meta = new V1ObjectMeta();
		V1ObjectMeta limit_meta = new V1ObjectMeta();
		
		
		namespace_meta.setName(data.getNamespace());
		quota_meta.setName(data.getNamespace());
		limit_meta.setName(data.getNamespace());
		
		V1Namespace namespacevo = new V1Namespace();
		V1ResourceQuota quotavo = data.getResourceQuota();
		V1LimitRange limitvo = data.getLimitRange();
		namespacevo.setApiVersion("v1");
		namespacevo.setKind("Namespace");
		namespacevo.setSpec(new V1NamespaceSpec().addFinalizersItem("kubernetes"));
		namespacevo.setMetadata(namespace_meta);
		quotavo.setApiVersion("v1");
		quotavo.setKind("ResourceQuota");
		quotavo.setMetadata(quota_meta);
		limitvo.setApiVersion("v1");
		limitvo.setKind("LimitRange");
		limitvo.setMetadata(quota_meta);
		
		namespacevo.getMetadata().setLabels(ResourcesLabelManager.getSystemLabels());
		quotavo.getMetadata().setLabels(ResourcesLabelManager.getSystemLabels());
		limitvo.getMetadata().setLabels(ResourcesLabelManager.getSystemLabels());
		String namespace = data.getNamespace();
		try {
			kubeCoreManager.createNamespace(namespace, namespacevo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				kubeCoreManager.editNamespace(namespace, namespacevo);
			}else {
				throw e;	
			}
		}
		
		try {
			kubeCoreManager.createLimitRange(namespace, limitvo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				String name = limitvo.getMetadata().getName();
				kubeCoreManager.editLimitRange(namespace, name, limitvo);
			}else {
				throw e;	
			}
		}
		
		try {
			kubeCoreManager.createResourceQuota(namespace, quotavo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				String name = limitvo.getMetadata().getName();
				kubeCoreManager.editResourceQuota(namespace, name, quotavo);
			}else {
				throw e;	
			}
		}
	}
	
	
	
	public void deleteClusterRoleBinding(KubeDeleteOptionsVO data) throws IOException, ApiException{
		kubeRbacAuthzManager.deleteClusterRoleBinding(data.getName(), data);
	}
	
	
	public void createRoleBinding(RoleBindingVO binding) throws IOException, ApiException{
		binding = makeRoleBinding(binding);
		
		try {
			//if name exist, new binding can't create
			kubeRbacAuthzManager.createRoleBinding(binding.getNamespace(), binding);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				LOG.debug("Conflict...");
			}else {
				throw e;	
			}
		}
	}
	
	public void editRoleBinding(RoleBindingVO binding) throws ApiException, IOException {
		binding = makeRoleBinding(binding);
		
		//1.delete RoleBinding
		KubeDeleteOptionsVO data = new KubeDeleteOptionsVO();
		data.setNamespace(binding.getNamespace());
		data.setUserName(binding.getUserName());
		deleteRoleBinding(data);

		//2.create RoleBinding
		kubeRbacAuthzManager.createRoleBinding(binding.getNamespace(), binding);
	}
	
	private RoleBindingVO makeRoleBinding(RoleBindingVO binding) {
		String username = binding.getUserName();
		String serviceAccountName = ResourcesNameManager.getServiceAccountName(username);
		String roleBindingName = ResourcesNameManager.getRoleBindingName(username);
		Map<String, String> labels = ResourcesLabelManager.getSystemUsernameLabels(username);
		
		V1ObjectMeta metadata = new V1ObjectMeta();
		metadata.setName(roleBindingName);
		metadata.setLabels(labels);
		metadata.setNamespace(binding.getNamespace());
		
		V1Subject subject = new V1Subject();
		subject.setKind("ServiceAccount");
		subject.setName(serviceAccountName);
		subject.setNamespace(systemNamespace);
		
		V1RoleRef roleRef = new V1RoleRef();
		roleRef.setApiGroup("rbac.authorization.k8s.io");
		roleRef.setKind("ClusterRole");
		roleRef.setName(binding.getClusterRole().getRole());
		
		List<V1Subject> subjects = new ArrayList<V1Subject>();
		subjects.add(subject);
		
		binding.setApiVersion("rbac.authorization.k8s.io/v1");
//		binding.setKind("RoleBinding");
		binding.setSubjects(subjects);
		binding.setRoleRef(roleRef);
		binding.setMetadata(metadata);
		
		return binding;
	}
	
	public void deleteRoleBinding(KubeDeleteOptionsVO data) throws IOException, ApiException{
		try {
			kubeRbacAuthzManager.deleteRoleBinding(data.getNamespace(), ResourcesNameManager.getRoleBindingName(data.getUserName()), data);
		}catch(ApiException e) {
			if(!e.getMessage().equals("Not Found")){
				throw e;
			}
		}
		
	}
	

	public void  createAndEditServiceAccount(String name, String namespace, ServiceAccountVO vo) throws ApiException {
		try {
			kubeCoreManager.createServiceAccount(vo.getNamespace(), vo);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				kubeCoreManager.editServiceAccount(name, vo.getNamespace(), vo);
			}else {
				throw e;	
			}
		}
	}
	
	public void createAndEditClusterRoleBinding(String username, V1ClusterRoleBinding clusterRoleBinding) throws ApiException {
		try {
			kubeRbacAuthzManager.createClusterRoleBinding( clusterRoleBinding);
		} catch (ApiException e) {
			if(e.getMessage().equals("Conflict")) {
				kubeRbacAuthzManager.editClusterRoleBinding(clusterRoleBinding.getMetadata().getName(), clusterRoleBinding);
			}else {
				throw e;	
			}
		}
	}
	
	public void createNamespaceLabel(String namespaceName, Map<String, String> label) throws ApiException, ParseException, ZcpException {
		V1Namespace namespace = getNamespace(namespaceName);
		if(namespace == null) {
			LOG.debug("namespace : " + namespace + "don't exist");
			throw new ZcpException("E00004");
		}else {
			namespace.getMetadata().setLabels(label);
			V1ObjectMeta meta = new V1ObjectMeta();
			meta.setLabels(label);
			String json = String.format("{\r\n" + 
					"	\"op\" : \"replace\",\r\n" + 
					"	\"path\" : \"/metadata/labels\",\r\n" + 
					"	\"labels\": {\"%s\" : \"%s\"}\r\n" + 
					"}", "test2", "1234");
			ArrayList<JsonObject> arr = new ArrayList<>();
		    arr.add(((JsonElement) deserialize(json, JsonElement.class)).getAsJsonObject());
			kubeCoreManager.editNamespaceLabel(namespaceName, arr);
		}
	}
	
	public Object deserialize(String jsonStr, Class<?> targetClass) {
	    Object obj = (new Gson()).fromJson(jsonStr, targetClass);
	    return obj;
	  }
	
}
