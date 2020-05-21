package com.skcc.cloudz.zcp.iam.manager;

import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.RbacAuthorizationV1Api;
import io.kubernetes.client.models.V1ClusterRole;
import io.kubernetes.client.models.V1ClusterRoleBinding;
import io.kubernetes.client.models.V1ClusterRoleBindingList;
import io.kubernetes.client.models.V1ClusterRoleList;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1RoleBinding;
import io.kubernetes.client.models.V1RoleBindingList;
import io.kubernetes.client.models.V1Status;
import io.kubernetes.client.util.Config;

@Component
public class KubeRbacAuthzManager {

	private final Logger log = (Logger) LoggerFactory.getLogger(KubeRbacAuthzManager.class);

	private ApiClient client;

	private RbacAuthorizationV1Api api;

	@Value("${kube.client.api.output.pretty}")
	private String pretty;

	public KubeRbacAuthzManager() throws IOException {
		client = Config.defaultClient();
		Configuration.setDefaultApiClient(client);
		api = new RbacAuthorizationV1Api(this.client);

		log.debug("KubeRbacAuthzManager is initialized");
	}

	public V1ClusterRole getClusterRole(String clusterRoleName) throws ApiException {
		return api.readClusterRole(clusterRoleName, pretty);
	}

	public V1ClusterRole createClusterRole(V1ClusterRole clusterRole) throws ApiException {
		return api.createClusterRole(clusterRole, pretty, null, null);
	}

	public V1ClusterRoleList getClusterRoles(String type) throws ApiException {
		String labelSelector = null;
		if (StringUtils.equals(type, "cluster")) {
			labelSelector = ResourcesLabelManager.getSystemClusterRoleLabelSelector();
		} else if (StringUtils.equals(type, "namespace")) {
			labelSelector = ResourcesLabelManager.getSystemNamespaceRoleLabelSelector();
		}

		return api.listClusterRole(pretty, null, null, labelSelector, null, null, null, null);
	}
	
	public V1ClusterRole addClusterRoleLabel(String clusterRoleName, Map<String, String> newLabel) throws ApiException {
		V1ClusterRole role = api.readClusterRole(clusterRoleName, pretty);
		V1ObjectMeta meta = role.getMetadata();

		for(Entry<String, String> e : newLabel.entrySet())
			meta.putLabelsItem(e.getKey(), e.getValue());

		// use "api.patchClusterRole(...)"
		// - https://github.com/kubernetes-client/java/issues/263#issuecomment-408806995
		// - https://github.com/kubernetes-client/java/issues/263#issuecomment-408806995
		return api.replaceClusterRole(clusterRoleName, role, pretty, null, null);
	}

	public V1ClusterRole removeClusterRoleLabel(String clusterRoleName, Map<String, String> newLabel) throws ApiException {
		V1ClusterRole role = api.readClusterRole(clusterRoleName, pretty);
		V1ObjectMeta meta = role.getMetadata();
		
		if(meta.getLabels() == null)
			return role;

		boolean modified = false;
		for(String k : newLabel.keySet()) {
			modified = meta.getLabels().remove(k) != null;
		}
		
		if(!modified)
			return role;

		return api.replaceClusterRole(clusterRoleName, role, pretty, null, null);
	}

	public V1ClusterRoleBindingList getClusterRoleBindingList() throws ApiException {
		return api.listClusterRoleBinding(pretty, null, null, ResourcesLabelManager.getSystemUserLabelSelector(),
				null, null, null, null);
	}

	public V1ClusterRoleBindingList getClusterRoleBindingListByUsername(String username) throws ApiException {
		return api.listClusterRoleBinding(pretty, null, null, 
				ResourcesLabelManager.getSystemUsernameLabelSelector(username), null, null, null, null);
	}

	public V1ClusterRoleBinding getClusterRoleBindingByUsername(String username) throws ApiException {
		return api.readClusterRoleBinding(ResourcesNameManager.getClusterRoleBindingName(username), pretty);
	}

	public V1ClusterRoleBinding createClusterRoleBinding(V1ClusterRoleBinding clusterrolebinding) throws ApiException {
		return api.createClusterRoleBinding(clusterrolebinding, pretty, null, null);
	}

	public V1Status deleteClusterRoleBinding(String clusterRoleBindingName) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);
		return api.deleteClusterRoleBinding(clusterRoleBindingName, pretty, deleteOptions, null, null, null, null);
	}

	public V1Status deleteClusterRoleBindingByUsername(String username) throws ApiException {
		return api.deleteCollectionClusterRoleBinding(pretty, null, null, 
				ResourcesLabelManager.getSystemUsernameLabelSelector(username), null, null, null, null);
	}

	public V1ClusterRoleBinding editClusterRoleBinding(String clusterRoleBindingName,
			V1ClusterRoleBinding clusterrolebinding) throws ApiException {
		return api.replaceClusterRoleBinding(clusterRoleBindingName, clusterrolebinding, pretty, null, null);
	}

	public V1RoleBindingList getRoleBindingListAllNamespaces() throws ApiException {
		return api.listRoleBindingForAllNamespaces(null, null, ResourcesLabelManager.getSystemUserLabelSelector(),
				null, pretty, null, null, null);
	}

	public V1RoleBindingList getRoleBindingListByNamespace(String namespace) throws ApiException {
		return api.listNamespacedRoleBinding(namespace, pretty, null, null, 
				ResourcesLabelManager.getSystemUserLabelSelector(), null, null, null, null);
	}

	public V1RoleBindingList getRoleBindingListByUsername(String username) throws ApiException {
		return api.listRoleBindingForAllNamespaces(null, null,
				ResourcesLabelManager.getSystemUsernameLabelSelector(username), null, pretty, null, null, null);
	}

	public V1RoleBinding createRoleBinding(String namespace, V1RoleBinding rolebinding) throws ApiException {
		return api.createNamespacedRoleBinding(namespace, rolebinding, pretty, null, null);
	}

	public V1RoleBinding getRoleBindingByUserName(String namespace, String username) throws ApiException {
		return api.readNamespacedRoleBinding(ResourcesNameManager.getRoleBindingName(username), namespace, pretty);
	}

	public V1RoleBinding replaceRoleBinding(String namespace, String roleBindingName, V1RoleBinding rolebinding)
			throws ApiException {
		return api.replaceNamespacedRoleBinding(roleBindingName, namespace, rolebinding, pretty, null, null);
	}

	public V1Status deleteRoleBinding(String namespace, String roleBindingName, V1DeleteOptions deleteOptions)
			throws ApiException {
		return api.deleteNamespacedRoleBinding(roleBindingName, namespace, pretty, deleteOptions, null, null, null, null);
	}

	public V1Status deleteRoleBindingListByUsername(String namespace, String username) throws ApiException {
		return api.deleteCollectionNamespacedRoleBinding(namespace, pretty, null, null, 
				ResourcesLabelManager.getSystemUsernameLabelSelector(username), null, null, null, null);
	}
}
