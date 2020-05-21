package com.skcc.cloudz.zcp.iam.common.config.websocket;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import com.skcc.cloudz.zcp.iam.common.actuator.SystemEndpoint.EndpointSource;
import com.skcc.cloudz.zcp.iam.common.config.websocket.WebSocketUtils.PodConnectionContext;
import com.skcc.cloudz.zcp.iam.manager.KubeCoreManager;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import io.kubernetes.client.ApiException;
import io.kubernetes.client.Exec;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ContainerStatus;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1SecretList;
import io.kubernetes.client.util.Yaml;

public class WebSshHandler4 extends PodExecRelayHanlder implements EndpointSource<Object> {
    @Value("${zcp.wsh.template}")
    private String templatePath = "classpath:/ssh/pod.yaml";

    @Value("${zcp.wsh.image}")
    private String image = "cloudzcp/wsh:latest";

    private String defaultNamespace = "zcp-system";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KubeCoreManager manager;

    // @Autowired
    // private KubeResourceManager resourceManager;

    private CoreV1Api coreApi = new CoreV1Api(client);

    private PodConnectionContext wsContext = new PodConnectionContext();

    private final WebSocketSession OUT_WAIT = new EmptyWebSocketSession() {
        private Map<String, Object> attr = Maps.newHashMap();
        public Map<String, Object> getAttributes() { return attr; }
    };

    /*
     * http://polarhome.com/service/man/?qf=ps&af=0&sf=0&of=Alpinelinux&tf=2
     * http://polarhome.com/service/man/?qf=pkill&af=0&sf=0&of=Alpinelinux&tf=2
     * 
     * watch ps -o pgid,ppid,pid,comm,time,tty,vsz,sid,stat,rss pkill -l pgrep -s
     * <session_id> pkill -s <session_id> -9
     */
    final String sid = "ps -o pid,sid | grep $$ | awk '{print $2}' | head -n 1";
    final String kill = String.format("pkill -9 -s $(%s) \r", sid);
    final String cleanup_script = String.format("nohup %s 2>&1 1>/tmp/pkill.$$.log &", kill);

    private boolean connected(WebSocketSession out) {
        return out != null && !OUT_WAIT.equals(out);
    }

    private boolean active(String podName, String namespace) {
        List<WebSocketSession> sessions = wsContext.getConnections(podName, namespace);
        return sessions.stream()
            .filter(in -> connected(in))
            .findFirst()
            .isPresent();
    }


    @Override
    protected WebSocketSession createSession(WebSocketSession in) throws Exception {
        // Aleady Bound
        WebSocketSession out = RELAY_SESSION.of(in);
        if(connected(out)) {
            log.info("Session is aleady bound. {} -> {}", in.getId(), out.getId());
            return out;
        }

        // Lock other threads
        RELAY_SESSION.to(in, OUT_WAIT);

        Map<String, String> vars = getQueryParams(in);
        final String podName = POD_NAME.to(in, StrSubstitutor.replace("web-ssh-${username}", vars));
        final String namespace = POD_NAMESPACE.to(in, vars.get("ns"));

        try {
            // Try to create a pod
            wsContext.putConnection(podName, namespace, in);
            V1Pod pod = manager.getPod(namespace, podName);
            if("Running".equals(pod.getStatus().getPhase())) {
                POD_CONTAINER.to(in, "alpine");
                out = super.createSession(in);
                CLEAN_UP_MESSAGE.to(out, Lists.newArrayList(cleanup_script));

                log.info("Create a connection for OUT({})", out.getId());
                wsContext.setOutOfSync(podName, null);
                return out;
            }
        } catch (ApiException e) {
            int code = e.getCode();
            boolean head = wsContext.isFirst(podName, namespace, in);
            if(code == 404 && head){
                log.info("Try to create SSH Pod at IN({}).", in.getId());
                createPod(in);
            } else {
                log.info("{} - {}", e.getCode(), e.getMessage());
                log.info("{}", e.getResponseBody());
            }
        }

        return OUT_WAIT;
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

    private String updateToken(String podName) throws ApiException {
        String token = wsContext.getVariable(podName, "token");
        return token == null ? "" : token;
        // String saName = "zcp-system-sa-" + username;
    
        // V1ServiceAccount sa = manager.getServiceAccount("zcp-system", saName);
        // V1ObjectReference ref = sa.getSecrets().get(0);
        // V1Secret secret = manager.getSecret("zcp-system", ref.getName());
    
        // byte[] token = secret.getData().get("token");
        // String tokenStr = new String(token);
    
        // return tokenStr;
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
        // for (String podName : wsContext.getOutOfSync()) {
        for (String podName : wsContext.getOutOfSync()) {
            wsContext.inSync(podName, null);

            Set<String> namespaces = wsContext.getNamespaces(podName);

            // update connetion counts
            for (String ns : namespaces) {
                int size = wsContext.getConnections(podName, ns).size();
                wsContext.putEnv(podName, "conn_" + ns, String.valueOf(size));
            }

            // update pod each namespaces
            for (String ns : wsContext.getNamespaces(podName)) {
                try {
                    StringBuilder content = wsContext.getEnvAsString(podName);
                    writeEnv(podName, ns, content.toString());

                    // FutureTask<Void> future = new FutureTask(() -> { writeEnv(podName, namespace, content.toString()); }, null);
                    // executor.execute(future);
                    // future.get(sync_wait_timeout, sync_wait_time_unit);

                    // Future<?> future = executor.submit(() -> { writeEnv(podName, namespace, content.toString()); });
                    // future.get(sync_wait_timeout, sync_wait_time_unit);

                // } catch (InterruptedException | ExecutionException | TimeoutException e) {
                //     log.info("Fail to sync envs. [pod={}, ns={}, thread={}]", podName, ns, Thread.currentThread().getName());
                //     log.debug("", e);

                //     wsContext.putConnection(podName, ns, EMPTY);
                } catch (Exception e) {
                    if (e instanceof ApiException) {
                        ApiException ae = (ApiException) e;
                        log.info("API Error :: {} - {}", ae.getCode(), ae.getMessage());
                        log.debug("{}", ae.getResponseBody());
                    }

                    log.debug("", e);
                }
            }
        }
    }

    private void writeEnv(String podName, String namespace, String content) {
        try {
            // update matched pod
            log.info("Update env variables. [pod={}, ns={}]\n{}", podName, namespace, content);

            log.info("Try to connect pod({}, ns={})", podName, namespace);
            Process ps = new Exec(client).exec(namespace, podName, new String[] { "sh", "-c", "tar xf - -C /" }, true);

            log.info("Wait to connect pod({}, ns={})", podName, namespace);
            boolean isClosed = ps.waitFor(1, TimeUnit.SECONDS);

            log.info("Handshake is done. ExecProcess is active({})", !isClosed);
            if (!isClosed) {
                File meta = new File(".env");
                writeFileAsTar(ps.getOutputStream(), meta, content.getBytes());
                ps.destroy();
                log.info("Finish to write env variables. [pod={}, ns={}]", podName, namespace);

                // close socket manually
                Object target = ps;
                String[] paths = new String[] { "streamHandler", "socket" };
                for (String path : paths) {
                    Field field = ReflectionUtils.findField(target.getClass(), path);
                    ReflectionUtils.makeAccessible(field);
                    target = field.get(target);
                }
                WebSocket.class.cast(target).close(0, "done");
                log.info("Close websocket to update env variables. [pod={}, ns={}]", podName, namespace);
            }
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
        try {
            // V1SecretList list = resourceManager.getList(defaultNamespace, "secret");
            V1SecretList list = coreApi.listNamespacedSecret(defaultNamespace, "true", null, null, null, null, null, null, null);
            for (V1Secret secret : list.getItems()) {
                String ns = secret.getMetadata().getNamespace();
                String name = secret.getMetadata().getName();
                String user = StringUtils.substringBetween(name, "zcp-system-sa-", "-token");

                if (user == null) continue;

                String key = "token";
                String podName = "web-ssh-" + user;
                String token = new String(secret.getData().get(key));
                String action = xxx(podName, key, token);

                if(!"NONE".equals(action))
                    log.info("{} {} ({}, ns={}).", action, key, podName, ns);
            }
        } catch (Exception e) {
            log.info("{}: {}", e.getClass(), e.getMessage());
            log.info("{}", e);
        }
    }

    private String xxx(final String group, final String key, final String val) {
        /*
         * Table (group, key, val)
         *   val = new value
         *   v0  = old value
         * 
         * Conditions
         *   ADD    : (g=active,   v0=null or not_equals)
         *   UPDATE : (g=active,   v0=not_equals)
         *   DELETE : (g=inactive, v0=not_null)
         *   NONE   : (g=active,   v0=equals)
         */
        final String TOKEN = wsContext.getVariable(group, key);
        final boolean ACTIVE = wsContext.connected(group);
        
        final boolean DELETE = !ACTIVE && TOKEN != null; 
        if (DELETE) {
            wsContext.removeVariable(group, key);
            return "DELETE";
        }

        boolean UPDATE = ACTIVE && !val.equals(TOKEN);
        if (UPDATE) {
            wsContext.putEnv(group, key, val);
            wsContext.setOutOfSync(group, null);
            return "UPDATE";
        }

        return "NONE";
    }

    @Scheduled(fixedDelay = 5000)
    public void watchPod() {
        final String labelSelector = "app=web-ssh";
        try {
            // V1PodList list = resourceManager.getList(defaultNamespace, "pod", labelSelector);
            V1PodList list = coreApi.listNamespacedPod("", "true", null, null, labelSelector, null, null, null, null);
            for (V1Pod pod : list.getItems()) {
                String ns = pod.getMetadata().getNamespace();
                String name = pod.getMetadata().getName();
                String status = pod.getStatus().getPhase();

                final String active_sessions = ns;
                final String wait_sessions = ns + "_wait";

                // final boolean ACTIVE = wsContext.connected(name, active_sessions);
                final boolean RUNNING = "Running".equals(status);
                final boolean TERMINATED = "Succeeded".equals(status) || "Failed".equals(status);

                final List<V1ContainerStatus> containers = pod.getStatus().getContainerStatuses();
                final int RESTART_COUNT = containers == null ? -1
                        : containers.stream().mapToInt(container -> container.getRestartCount()).sum();

                if (TERMINATED || 3 <= RESTART_COUNT) {
                    log.info("DELETE {} pod ({}, ns={}, restart={})", status, name, ns, RESTART_COUNT);
                    try {
                        manager.deletePod(ns, name);
                        for(WebSocketSession in : wsContext.getConnections(name, ns))
                            this.getRelaySession(in);
                        continue;
                    } catch (ApiException ae) {
                        if (ae.getCode() != 404) {
                            log.error("Fail to handle watch event. [type={}, msg={}({})]", V1Pod.class.getSimpleName(), ae.getMessage(), ae.getCode());
                            log.debug("Fail to handle watch event. [type={}, body]\n{}", V1Pod.class.getSimpleName(), ae.getResponseBody());
                            throw ae;
                        }
                    }
                }

                if (RUNNING) {
                    for (WebSocketSession in : wsContext.getConnections(name, ns)) {
                        this.getRelaySession(in);
                    }
                }
            }
        } catch (Exception e) {
            log.info("{}: {}", e.getClass(), e.getMessage());
            log.debug("{}", e);
        }
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