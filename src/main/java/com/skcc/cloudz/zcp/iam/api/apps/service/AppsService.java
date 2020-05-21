package com.skcc.cloudz.zcp.iam.api.apps.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.skcc.cloudz.zcp.iam.common.exception.ZcpErrorCode;
import com.skcc.cloudz.zcp.iam.common.exception.ZcpException;
import com.skcc.cloudz.zcp.iam.manager.KubeAppsManager;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Deployment;
import io.kubernetes.client.models.V1DeploymentList;

@Service
public class AppsService {

	@SuppressWarnings("unused")
	private final Logger log = LoggerFactory.getLogger(AppsService.class);

	@Autowired
	private KubeAppsManager kubeAppsManager;

	// The IllegalArgumentException is thrown during json binding
	public List<String> getDeployments(String namespace) throws ZcpException {
		V1DeploymentList v1DeploymentList = null;
		try {
		    v1DeploymentList = kubeAppsManager.getDeploymentList(namespace);
		} catch (ApiException e) {
			throw new ZcpException(ZcpErrorCode.DEPOLYMENT_LIST_ERROR, e);
		}
		
		List<String> deployments = new ArrayList<>();
		for (V1Deployment v1Deployment : v1DeploymentList.getItems()) {
			deployments.add(v1Deployment.getMetadata().getName());
		}

		return deployments;
	}

}
