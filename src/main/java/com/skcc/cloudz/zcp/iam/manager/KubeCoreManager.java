package com.skcc.cloudz.zcp.iam.manager;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.common.reflect.TypeToken;
import com.google.gson.JsonSyntaxException;
import com.squareup.okhttp.Call;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.ApiResponse;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1LimitRange;
import io.kubernetes.client.models.V1LimitRangeList;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import io.kubernetes.client.models.V1NodeList;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1ResourceQuota;
import io.kubernetes.client.models.V1ResourceQuotaList;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1SecretList;
import io.kubernetes.client.models.V1ServiceAccount;
import io.kubernetes.client.models.V1ServiceAccountList;
import io.kubernetes.client.models.V1Status;
import io.kubernetes.client.util.Config;

@Component
public class KubeCoreManager {

	private final Logger logger = (Logger) LoggerFactory.getLogger(KubeCoreManager.class);

	private ApiClient client;

	private CoreV1Api api;

	@Value("${kube.client.api.output.pretty}")
	private String pretty;

	public KubeCoreManager() throws IOException {
		client = Config.defaultClient();
		Configuration.setDefaultApiClient(client);
		api = new CoreV1Api(this.client);

		logger.debug("KubeCoreManager is initialized");
	}

	public V1Pod createPod(String namespace, V1Pod body) throws ApiException {
		return api.createNamespacedPod(namespace, body, pretty, null, null);
	}

	public V1Pod getPod(String namespace, String name) throws ApiException {
		return api.readNamespacedPod(name, namespace, pretty, null, null);
	}

	public V1Pod deletePod(String namespace, String name) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);

		/*
		 * https://github.com/kubernetes-client/java/issues/86#issuecomment-334981383
		 * CoreV1Api.deleteNamespacedPodWithHttpInfo(...)
		 */
		Call call = api.deleteNamespacedPodCall(name, namespace, pretty, deleteOptions, null, null, null, null, null, null);
        Type localVarReturnType = new TypeToken<V1Pod>(){}.getType();
		ApiResponse<V1Pod> resp = client.execute(call, localVarReturnType);
		return resp.getData();
		// return api.deleteNamespacedPod(name, namespace, deleteOptions, pretty, null, null, null);
	}

	public V1ServiceAccount createServiceAccount(String namespace, V1ServiceAccount serviceAccount)
			throws ApiException {
		return api.createNamespacedServiceAccount(namespace, serviceAccount, pretty, null, null);
	}

	public V1ServiceAccount getServiceAccount(String namespace, String serviceAccountName) throws ApiException {
		return api.readNamespacedServiceAccount(serviceAccountName, namespace, pretty, null, null);
	}

	public V1ServiceAccount editServiceAccount(String namespace, String serviceAccountName,
			V1ServiceAccount serviceAccount) throws ApiException {
		return api.replaceNamespacedServiceAccount(serviceAccountName, namespace, serviceAccount, pretty, null, null);
	}

	public V1Status deleteServiceAccount(String namespace, String serviceAccountName) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);
		return api.deleteNamespacedServiceAccount(serviceAccountName, namespace, pretty, deleteOptions, null, null,
				null, null);
	}

	public V1Status deleteServiceAccountListByUsername(String namespace, String username) throws ApiException {
		return api.deleteCollectionNamespacedServiceAccount(namespace, pretty, null, null,
				ResourcesLabelManager.getSystemUsernameLabelSelector(username), null, null, null, null);
	}

	public V1ServiceAccountList getServiceAccountListByUsername(String namespace, String username) throws ApiException {
		return api.listNamespacedServiceAccount(namespace, pretty, null, null, 
				ResourcesLabelManager.getSystemUsernameLabelSelector(username), null, null, null, null);
	}

	public V1Secret getSecret(String namespace, String secretName) throws ApiException {
		return api.readNamespacedSecret(secretName, namespace, pretty, null, null);
	}

	public V1SecretList getSecretList(String namespace, List<String> types) throws ApiException {
		V1SecretList list = api.listNamespacedSecret(namespace, pretty, null, null, null, null, null, null, null);
		
		if(types == null || types.isEmpty())
			return list;

		// filter
		Iterator<V1Secret> iter = list.getItems().iterator();
		while(iter.hasNext()) {
			if(!types.contains(iter.next().getType()))
				iter.remove();
		}
		
		return list;
	}
	
	public V1Secret createSecret(String namespace, V1Secret secret) throws ApiException {
		return api.createNamespacedSecret(namespace, secret, pretty, null, null);
	}

	public V1Status deleteSecret(String namespace, String secretName) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);
		return api.deleteNamespacedSecret(secretName, namespace, pretty, deleteOptions, null, null, null, null);
	}

	public V1NamespaceList getNamespaceList() throws ApiException {
		String labelSelector = ResourcesLabelManager.getSystemLabelSelector();
		return api.listNamespace(pretty, null, null, labelSelector, null, null, null, null);
	}

	public V1Namespace createNamespace(String namespaceName, V1Namespace namespace) throws ApiException {
		return api.createNamespace(namespace, pretty, null, null);
	}

	public V1Namespace getNamespace(String namespace) throws ApiException {
		return api.readNamespace(namespace, pretty, null, null);
	}

	public V1Namespace editNamespace(String quotaName, V1Namespace namespace) throws ApiException {
		return api.replaceNamespace(quotaName, namespace, pretty, null, null);
	}

	public V1Namespace replaceNamespace(String namespaceName, V1Namespace namespace) throws ApiException {
		return api.replaceNamespace(namespaceName, namespace, pretty, null, null);
	}

	public V1Status deleteNamespace(String namespace) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);

		V1Status status = null;
		try {
			status = api.deleteNamespace(namespace, pretty, deleteOptions, null, null, null, null);
		} catch (JsonSyntaxException e) {
			if (e.getCause() instanceof IllegalStateException) {
				// TODO we shoud check why exception is thrown??
				// this is an addhoc process
//				e.printStackTrace();
				logger.warn("Why does the k8s client throw the exception?? {}", e.getMessage());
			} else {
				throw e;
			}
		}

		return status;
	}

	public V1LimitRangeList getLimitRanges(String namespace) throws ApiException {
		return api.listNamespacedLimitRange(namespace, pretty, null, null, null, null, null, null, null);
	}

	public V1LimitRange createLimitRange(String namespace, V1LimitRange limitRange) throws ApiException {
		return api.createNamespacedLimitRange(namespace, limitRange, pretty, null, null);
	}

	public V1LimitRange getLimitRange(String namespace, String limitRangeName) throws ApiException {
		return api.readNamespacedLimitRange(limitRangeName, namespace, pretty, null, null);
	}

	public V1LimitRange editLimitRange(String namespace, String limitRangeName, V1LimitRange limitRange)
			throws ApiException {
		return api.replaceNamespacedLimitRange(limitRangeName, namespace, limitRange, pretty, null, null);
	}

	public V1Status deleteLimitRange(String namespace, String limitRangeName) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);
		return api.deleteNamespacedLimitRange(limitRangeName, namespace, pretty, deleteOptions, null, null, null, null);
	}

	public V1ResourceQuotaList getResourceQuotaList(String namespace) throws ApiException {
		return api.listNamespacedResourceQuota(namespace, pretty, null, null, null, null, null, null, null);
	}

	public V1ResourceQuota createResourceQuota(String namespace, V1ResourceQuota quota) throws ApiException {
		return api.createNamespacedResourceQuota(namespace, quota, pretty, null, null);
	}

	public V1ResourceQuota editResourceQuota(String namespace, String quotaName, V1ResourceQuota quota)
			throws ApiException {
		return api.replaceNamespacedResourceQuota(quotaName, namespace, quota, pretty, null, null);
	}

	public V1ResourceQuota getResourceQuota(String namespace, String quotaName) throws ApiException {
		return api.readNamespacedResourceQuota(quotaName, namespace, pretty, null, null);
	}

	public V1ResourceQuotaList getAllResourceQuotaList() throws ApiException {
		return api.listResourceQuotaForAllNamespaces(null, null, null, null, pretty, null, null, null);
	}

	public V1Status deleteResourceQuota(String namespace, String resourceQuotaName) throws ApiException {
		V1DeleteOptions deleteOptions = new V1DeleteOptions();
		deleteOptions.setGracePeriodSeconds(0l);
		return api.deleteNamespacedResourceQuota(resourceQuotaName, namespace, pretty, deleteOptions, null, null, null, null);
	}

	public V1NodeList getNodeList() throws ApiException {
		return api.listNode(pretty, null, null, null, null, null, null, null);
	}

	public V1PodList getAllPodList() throws ApiException {
		return api.listPodForAllNamespaces(null, null, null, null, pretty, null, null, null);
	}

	public V1PodList getPodListByNode(String nodeName) throws ApiException {
		StringBuilder fieldSelector = new StringBuilder();
		fieldSelector.append("spec.nodeName=");
		fieldSelector.append(nodeName);
		fieldSelector.append(",status.phase!=Failed,status.phase!=Succeeded");

		return api.listPodForAllNamespaces(null, fieldSelector.toString(), null, null, pretty, null, null, null);
	}

	public V1PodList getPodListByNamespace(String namespace) throws ApiException {
		return api.listNamespacedPod(namespace, pretty, null, null, null, null, null, null, null);
	}

}
