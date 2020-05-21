package com.skcc.cloudz.zcp.iam.common.config.websocket;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.google.common.reflect.TypeToken;
import com.skcc.cloudz.zcp.iam.common.actuator.SystemEndpoint.EndpointSource;
import com.skcc.cloudz.zcp.iam.common.config.websocket.WebSocketUtils.PodConnectionContext;
import com.skcc.cloudz.zcp.iam.manager.KubeCoreManager;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.ws.WebSocket;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.Exec;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ContainerStatus;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1ObjectReference;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1ServiceAccount;
import io.kubernetes.client.util.Watch;
import io.kubernetes.client.util.Watch.Response;
import io.kubernetes.client.util.Yaml;

public class WebSshHandler3 extends PodExecRelayHanlder implements EndpointSource<Object> {
    private final Type TYPE_SECRET = new TypeToken<Watch.Response<V1Secret>>() {}.getType();
    private final Type TYPE_POD = new TypeToken<Watch.Response<V1Pod>>() {}.getType();

    @Value("${zcp.wsh.template}")
    private String templatePath = "classpath:/ssh/pod.yaml";

	@Value("${zcp.wsh.image}")
	private String image = "cloudzcp/wsh:latest";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KubeCoreManager manager;

    private CoreV1Api coreV1Api = new CoreV1Api(client);
    private PodConnectionContext wsContext = new PodConnectionContext();

    private final WebSocketSession EMPTY = new EmptyWebSocketSession() {
        private Map<String, Object> attr = Maps.newHashMap();
        public Map<String, Object> getAttributes() { return attr; }
    };

    private long sync_wait_timeout = 100;
    private TimeUnit sync_wait_time_unit = TimeUnit.SECONDS;

    @Autowired
    private TaskExecutor executor;

    @Override
    protected WebSocketSession createSession(WebSocketSession in) throws Exception {
        Map<String, String> vars = getQueryParams(in);

        String namespace = vars.get("ns");
        String username = vars.get("username");
        String podName = StrSubstitutor.replace("web-ssh-${username}", vars);

        try {
            HANDLER.to(in, this);
            POD_NAME.to(in, podName);
            POD_NAMESPACE.to(in, namespace);
            POD_CONTAINER.to(in, "alpine");

            wsContext.putConnection(podName, namespace, in);

            V1Pod pod = manager.getPod(namespace, podName);
            if("Running".equals(pod.getStatus().getPhase())){
                wsContext.putEnv(podName, "token", updateToken(username));
                wsContext.setOutOfSync(podName, namespace);

                WebSocketSession out = super.createSession(in);
                List<String> cleanup = Lists.newArrayList();
                CLEAN_UP_MESSAGE.to(out, cleanup);
                /*
                 * http://polarhome.com/service/man/?qf=ps&af=0&sf=0&of=Alpinelinux&tf=2
                 * http://polarhome.com/service/man/?qf=pkill&af=0&sf=0&of=Alpinelinux&tf=2
                 * 
                 * watch ps -o pgid,ppid,pid,comm,time,tty,vsz,sid,stat,rss
                 * pkill -l
                 * pgrep -s <session_id>
                 * pkill -s <session_id> -9
                 */
                String sid = "ps -o pid,sid | grep $$ | awk '{print $2}' | head -n 1";
                String kill = String.format("pkill -9 -s $(%s) \r", sid);
                String cmd = String.format("nohup %s 2>&1 1>/tmp/pkill.$$.log &", kill);

                cleanup.add(cmd);

                return out;
            }

            return EMPTY;
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                if (wsContext.isFirst(podName, namespace, in))
                    createPod(in);

                return EMPTY;
            }
            throw e;
        }
    }

    private V1Pod createPod(WebSocketSession in) throws ApiException, IOException {
        sendSystemMessage(in, "be creating pod...");

        Map<String, String> vars = getQueryParams(in);
        
        String namespace = vars.get("ns");
        String username = vars.get("username");
        String token = updateToken(username);
        String host = getHostName();

        // add context variables
        vars.put("namespace", namespace);
        vars.put("name.suffix", username);
        vars.put("var_namespace", wsContext.asShellSafe(namespace));
        vars.put("token", token);
        vars.put("iam.host", host);
        vars.put("image", image);

        // read config.xml template
        TextStringBuilder template = new TextStringBuilder();
        Resource resource = context.getResource(templatePath);
        Resources.asCharSource(resource.getURL(), Charset.forName("UTF-8")).copyTo(template);

        // create request
        new StringSubstitutor(vars).replaceIn(template);
        V1Pod spec = Yaml.loadAs(template.asReader(), V1Pod.class);
        String podName = spec.getMetadata().getName();

        wsContext.putEnv(podName, "iam.host", getHostName());
        wsContext.putEnv(podName, "token", token);

        POD_NAME.to(in, podName);
        return manager.createPod(namespace, spec);
    }

    private String updateToken(String username) throws ApiException {
        String saName = "zcp-system-sa-" + username;

        V1ServiceAccount sa = manager.getServiceAccount("zcp-system", saName);
        V1ObjectReference ref = sa.getSecrets().get(0);
        V1Secret secret = manager.getSecret("zcp-system", ref.getName());

        byte[] token = secret.getData().get("token");
        String tokenStr = new String(token);

        return tokenStr;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession in, CloseStatus status) throws Exception {
        try {
            super.afterConnectionClosed(in, status);
        } finally {
            String podName = POD_NAME.of(in);
            boolean removed = wsContext.removeAll(podName, in);

            if (removed) {
                wsContext.setOutOfSync(podName, null);
            }
        }
    }

    /*
     * Update ENV variables for web-ssh pod controlling
     */
    @Scheduled(fixedDelay = 5000)
    public void syncEnv() {
        for (String podName : wsContext.getOutOfSync()) {
            for (String ns : wsContext.getNamespaces(podName)) {
                doSyncPodEnv(podName, ns);
            }
            wsContext.inSync(podName, null);
        }
    }

    private void doSyncPodEnv(String podName, String namespace) {
        try {
            // update connetion counts
            Map<String, List<WebSocketSession>> pods = wsContext.getConnections(podName);

            for (String ns : pods.keySet()) {
                int size = wsContext.getConnections(podName, ns).size();
                wsContext.putEnv(podName, "conn_" + ns, String.valueOf(size));
            }

            StringBuilder content = wsContext.getEnvAsString(podName);

            // update matched pod
            for (String ns : pods.keySet()) {
                if (namespace != null && !ns.equals(namespace))
                    continue;

                FutureTask<Void> future = new FutureTask(() -> { writeEnv(podName, namespace, content.toString()); }, null);
                executor.execute(future);
                future.get(sync_wait_timeout, sync_wait_time_unit);
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            log.info("Fail to sync envs. [pod={}, ns={}, thread={}]", podName, namespace, Thread.currentThread().getName());
            log.debug("", e);

            wsContext.putConnection(podName, namespace, EMPTY);
        } catch (Exception e) {
            if (e instanceof ApiException) {
                ApiException ae = (ApiException) e;
                log.info("API Error :: {} - {}", ae.getCode(), ae.getMessage());
                log.debug("{}", ae.getResponseBody());
            }

            log.debug("", e);
        }
    }

    private void writeEnv(String podName, String namespace, String content) {
        try {
            if ("0".equals(wsContext.getVariable(podName, "conn_" + namespace))) {
                /*
                 * When try to send deleted pod, the thead is wait to connect forever with read-timeout zero(0).
                 * So remove pod of namesapce from a connection registry.
                 * 
                 * Related source codes.
                 * - WebSocketStreamHandler.open()  # this.notifyAll()
                 * - WebSocketStreamHandler$WebSocketOutputStream.write()  # this.wait() when this.socket == null
                 */
                wsContext.removeConnections(podName, namespace);
            }

            // update matched pod
            log.debug("Update ssh pod env variables. [pod={}, ns={}]\n{}", podName, namespace, content);

            File meta = new File(".env");
            Process ps = new Exec(client).exec(namespace, podName, new String[] { "sh", "-c", "tar xf - -C /" }, true);
            writeFileAsTar(ps.getOutputStream(), meta, content.getBytes());
            ps.destroy();
            log.debug("Finish to write env variables. [pod={}, ns={}]", podName, namespace);

            // close socket manually
            Object target = ps;
            String[] paths = new String[] { "streamHandler", "socket" };
            for (String path : paths) {
                Field field = ReflectionUtils.findField(target.getClass(), path);
                ReflectionUtils.makeAccessible(field);
                target = field.get(target);
            }
            WebSocket.class.cast(target).close(0, "done");
            log.debug("Close websocket to update env variables. [pod={}, ns={}]", podName, namespace);
        } catch (Exception e) {
            if (e instanceof ApiException) {
                ApiException ae = (ApiException) e;
                log.info("API Error :: {} - {}", ae.getCode(), ae.getMessage());
                log.debug("{}", ae.getResponseBody());
            }

            log.debug("", e);
        }
    }

    private void writeFileAsTar(OutputStream out, File meta, byte[] content) throws IOException {
        // https://memorynotfound.com/java-tar-example-compress-decompress-tar-tar-gz-files/
        TarArchiveOutputStream tar = new TarArchiveOutputStream(out);
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
        tar.setAddPaxHeadersForNonAsciiNames(true);

        TarArchiveEntry entry = new TarArchiveEntry(meta);
        entry.setSize(content.length);
        tar.putArchiveEntry(entry);
        IOUtils.write(content, tar);
        tar.closeArchiveEntry();
        tar.close();
    }

    /*
     * Handle changes of resources
     */
    @Scheduled(fixedDelay = 5000)
    public void watchSecret() {
        Watch<V1Secret> watch = null;
        try {
            Call call = coreV1Api.listSecretForAllNamespacesCall(null, null, null, null, null, null, null, Boolean.TRUE, null, null);
            watch = Watch.createWatch(client, call, TYPE_SECRET);
            doWatch(watch, TYPE_SECRET);
        } catch (Exception e) {
            log.error("{}", e.getMessage());
            log.debug("", e);
        } finally {
            IOUtils.closeQuietly(watch);
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void watchPod() {
        Watch<V1Pod> watch = null;
        try {
            String labelSelector = "app=web-ssh";
            Call call = coreV1Api.listPodForAllNamespacesCall(null, null, labelSelector, null, null, null, null, Boolean.TRUE, null, null);

            watch = Watch.createWatch(client, call, TYPE_POD);
            doWatch(watch, TYPE_POD);
        } catch (Exception e) {
            log.error("{}", e.getMessage());
            log.debug("", e);
        } finally {
            IOUtils.closeQuietly(watch);
        }
    }

    private <T> void doWatch(Watch<T> watch, Type paramType) throws Exception {
        log.trace("Check watch events about {}", paramType.getTypeName());

        for (Response<T> res : watch) {
            if (log.isDebugEnabled()) {
                String type = "<unknown>";
                String name = "<unknown>";
                if (res.object != null) {
                    Class<?> clazz = res.object.getClass();
                    type = clazz.getSimpleName();

                    Field metaF = ReflectionUtils.findField(clazz, "metadata", V1ObjectMeta.class);
                    ReflectionUtils.makeAccessible(metaF);
                    V1ObjectMeta meta = (V1ObjectMeta) ReflectionUtils.getField(metaF, res.object);
                    name = meta.getName();
                }

                log.debug("Catch resource change event. {}({}) is {}.", name, type, res.type);
            }

            if (TYPE_SECRET.equals(paramType)) {
                handleWatchEvent((V1Secret) res.object, (Response<V1Secret>) res);
            } else if (TYPE_POD.equals(paramType)) {
                handleWatchEvent((V1Pod) res.object, (Response<V1Pod>) res);
            } else {
                // TODO: Unsupported Type
            }
        }
    }

    private void handleWatchEvent(V1Pod pod, Response<V1Pod> res) throws Exception {
        String ns = pod.getMetadata().getNamespace();
        String name = pod.getMetadata().getName();
        String status = pod.getStatus().getPhase();

        final boolean DELETE = "DELETE".equals(res.type);
        final boolean RUNNING = "Running".equals(status);
        final boolean SUCCEEDED = "Succeeded".equals(status);

        final List<V1ContainerStatus> containers = pod.getStatus().getContainerStatuses();
        final int RESTART_COUNT = containers == null ? -1
                : containers.stream().mapToInt(container -> container.getRestartCount()).sum();

        if (DELETE) {
            return;
        }

        if (SUCCEEDED || 3 <= RESTART_COUNT) {
            log.info("Delete unused ssh pod({}, ns={})", name, ns);
            try {
                manager.deletePod(pod.getMetadata().getNamespace(), name);
                return;
            } catch (ApiException ae) {
                if (ae.getCode() != 404) {
                    log.error("Fail to handle watch event. [type={}, msg={}({})]", V1Pod.class.getSimpleName(), ae.getMessage(), ae.getCode());
                    log.debug("Fail to handle watch event. [type={}, body]\n{}", V1Pod.class.getSimpleName(), ae.getResponseBody());
                    throw ae;
                }
            }
        }

        if (RUNNING) {
            if (!wsContext.connected(name, ns)) {
                return;
            }

            log.info("Start connecting to ssh pod({}, ns={})", name, ns);
            for (WebSocketSession in : wsContext.getConnections(name, ns)) {
                WebSshHandler3 handler = HANDLER.of(in);
                handler.getRelaySession(in);
            }
        }
    }

    private void handleWatchEvent(V1Secret secret, Response<V1Secret> res) throws Exception {
        String name = secret.getMetadata().getName();
        String user = StringUtils.substringBetween(name, "zcp-system-sa-", "-token");

        if (user == null || "DELETED".equals(res.type))
            return;

        log.debug("'{}' is {}. update token of '{}'.", name, res.type, user);

        String podName = "web-ssh-" + user;
        if (!wsContext.connected(podName))
            return;

        String token = new String(secret.getData().get("token"));
        wsContext.putEnv(podName, "token", token);
        wsContext.setOutOfSync(podName, null);
    }

    private String getHostName() {
        String cand = SystemUtils.getHostName();
        if (cand == null) {
            try {
                cand = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                cand = Arrays.toString(context.getEnvironment().getActiveProfiles());
            }
        }

        return cand;
    }

    /* for actuator (/system) */
    @Override
    public String getEndpointPath() {
        return "/wsh/envs";
    }

    @Override
    public Object getEndpointData(Map<String, Object> vars) {
        return wsContext.getPodNames()
                    .stream()
                    .map(p->wsContext.getVariables(p))
                    .collect(Collectors.toList());
    }
}