package com.skcc.cloudz.zcp.iam.manager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;

import com.google.common.collect.Maps;
import com.skcc.cloudz.zcp.iam.manager.client.ServiceAccountApiKeyAuth;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.auth.Authentication;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1SecretList;
import io.kubernetes.client.util.ClientBuilder;

@Component
public class KubeResourceManager {
	private final Logger logger = (Logger) LoggerFactory.getLogger(KubeResourceManager.class);

	private ApiClient client;
	private CoreV1Api api;

	@Value("${kube.client.api.output.pretty}")
	private String pretty;

	public KubeResourceManager() throws IOException {
		ClientBuilder builder = ClientBuilder.standard();
		client = builder.build();
		
		Map<String, Authentication> auth = Maps.newHashMap(client.getAuthentications());
		auth.put("BearerToken", new ServiceAccountApiKeyAuth("header", "authorization"));
		auth = Collections.unmodifiableMap(auth);

		Field field = ReflectionUtils.findField(ApiClient.class, "authentications");
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, client, auth);
		field.setAccessible(false);
		
		builder.getAuthentication().provide(client);

		api = new CoreV1Api(this.client);

		logger.debug("KubeCoreManager is initialized");
	}

	public V1SecretList getSecretList(String namespace, List<String> types) throws ApiException {
		V1SecretList list = api.listNamespacedSecret(namespace, pretty, null, null, null, null, null, null, null, null);

		if (types == null || types.isEmpty())
			return list;

		// filter
		Iterator<V1Secret> iter = list.getItems().iterator();
		while (iter.hasNext()) {
			if (!types.contains(iter.next().getType()))
				iter.remove();
		}

		return list;
	}
}
