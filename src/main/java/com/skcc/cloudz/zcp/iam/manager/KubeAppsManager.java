package com.skcc.cloudz.zcp.iam.manager;

import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.models.V1DeploymentList;
import io.kubernetes.client.util.Config;

@Component
public class KubeAppsManager {

	private final Logger logger = (Logger) LoggerFactory.getLogger(KubeAppsManager.class);

	private ApiClient client;

	private AppsV1Api api;

	@Value("${kube.client.api.output.pretty}")
	private String pretty;

	public KubeAppsManager() throws IOException {
		client = Config.defaultClient();
		Configuration.setDefaultApiClient(client);
		api = new AppsV1Api(this.client);

		logger.debug("KubeAppsManager is initialized");
	}

	public V1DeploymentList getDeploymentList(String namespace) throws ApiException {
	    V1DeploymentList v1DeploymentList = null;
		if (StringUtils.isEmpty(namespace)) {
			v1DeploymentList = api.listDeploymentForAllNamespaces(null, null, null, null, pretty, null, null, null);
		} else {
			v1DeploymentList = api.listNamespacedDeployment(namespace, pretty, null, null, null, null, null, null,
					null);
		}

		return v1DeploymentList;
	}

}
