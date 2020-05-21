package com.skcc.cloudz.zcp.iam.common.config.websocket;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.common.io.Resources;
import com.google.common.reflect.TypeToken;
import com.skcc.cloudz.zcp.iam.common.config.websocket.WebSocketUtils.MultimapTable;
import com.skcc.cloudz.zcp.iam.common.config.websocket.WebSocketUtils.ResourceWatcher;
import com.skcc.cloudz.zcp.iam.common.config.websocket.WebSocketUtils.TimeoutExecutorService;
import com.skcc.cloudz.zcp.iam.manager.KubeCoreManager;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.ws.WebSocket;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.StrSubstitutor;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.TextStringBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Exec;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ObjectReference;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1Secret;
import io.kubernetes.client.models.V1ServiceAccount;
import io.kubernetes.client.util.Watch;
import io.kubernetes.client.util.Watch.Response;
import io.kubernetes.client.util.Yaml;

public class WebSshHandler2 extends PodExecRelayHanlder {
    // @Value("${jenkins.template.folder}")
    private String templatePath = "classpath:/ssh/pod.yaml";

    @Autowired
    private ApplicationContext context;

    @Autowired
    private KubeCoreManager manager;

    // https://www.baeldung.com/guava-multimap
    // https://github.com/google/guava/wiki/NewCollectionTypesExplained#multimap
    // ( pod-name, namespace, [sessions] )
    private MultimapTable<String, String, WebSocketSession> conns = MultimapTable.cretae();

    // https://www.baeldung.com/guava-table
    // ( pod-name, env-key, env-val )
    private Table<String, String, String> envs = Tables.synchronizedTable(HashBasedTable.create());

    private final WebSocketSession EMPTY = new EmptyWebSocketSession() {
        private Map<String, Object> attr = Maps.newHashMap();

        public Map<String, Object> getAttributes() {
            return attr;
        }
    };

    private ResourceWatcher<V1Secret> secretWatcher = new SecretWatcher(client);
    private ResourceWatcher<V1Pod> podWatcher = new PodWatcher(client);

    @Override
    protected WebSocketSession createSession(WebSocketSession in) throws Exception {
        Map<String, String> vars = getQueryParams(in);

        String namespace = vars.get("ns");
        String podName = StrSubstitutor.replace("web-ssh-${username}", vars);

        try {
            HANDLER.to(in, this);
            POD_NAME.to(in, podName);
            POD_NAMESPACE.to(in, namespace);
            POD_CONTAINER.to(in, "alpine");

            conns.putValue(podName, namespace, in);
                
            V1Pod pod = manager.getPod(namespace, podName);
            if("Running".equals(pod.getStatus().getPhase())){
                syncEnv(podName, namespace);

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
        } catch(ApiException e) {
            if(e.getCode() == 404){
                int rank = conns.indexOf(podName, namespace, in);
                if(rank == 0)
                    createPod(in);
                return EMPTY;
            }
            throw e;
        }
    }

    private V1Pod createPod(WebSocketSession in) throws ApiException, IOException {
        Map<String, String> vars = getQueryParams(in);

        String namespace = vars.get("ns");
        String username = vars.get("username");
        String saName = "zcp-system-sa-" + username;

        // add context variables
        vars.put("namespace", namespace);
        vars.put("name.suffix", username);

        V1ServiceAccount sa = manager.getServiceAccount("zcp-system", saName);
        V1ObjectReference ref = sa.getSecrets().get(0);
        V1Secret secret = manager.getSecret("zcp-system", ref.getName());
        byte[] token = secret.getData().get("token");
        String tokenStr = new String(token);
        vars.put("token", tokenStr);

        // read config.xml template
        TextStringBuilder template = new TextStringBuilder();
        Resource resource = context.getResource(templatePath);
        Resources.asCharSource(resource.getURL(), Charset.forName("UTF-8")).copyTo(template);

        // create request
        new StringSubstitutor(vars).replaceIn(template);
        V1Pod spec = Yaml.loadAs(template.asReader(), V1Pod.class);
        String podName = spec.getMetadata().getName();

        envs.put(podName, "token", tokenStr);

        POD_NAME.to(in, podName);
        return manager.createPod(namespace, spec);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession in, CloseStatus status) throws Exception {
        try {
            super.afterConnectionClosed(in, status);
        } finally {
            String podName = POD_NAME.of(in);
            boolean removed = conns.removeAll(podName, in);

            if(removed){
                syncEnv(podName, null);
            }
        }
    }

    public void syncEnv(String podName, String namespace){
        EnvSyncTask task = new EnvSyncTask();
        task.podName = podName;
        task.namespace = namespace;
        task.timeout = 5;  // seconds
        task.execute();
    }

    private class EnvSyncTask extends TimeoutExecutorService {
        private String namespace, podName;

        protected void onFailure(Exception e) {
            log.info("Fail to sync envs. [pod={}, ns={}, thread={}]", podName, namespace, current.getName());
            super.onFailure(e);
        }

        public void run() {
            current = Thread.currentThread();
            try {
                // update connetion counts
                Map<String, List<WebSocketSession>> pods = conns.row(podName);
                System.out.println(pods);
                for(String ns: pods.keySet()) {
                    int size = conns.get(podName, ns).size();
                    envs.put(podName, "conn_" + ns, String.valueOf(size));
                }

                // create env file
                StringBuilder content = new StringBuilder();
                Map<String, String> env = envs.row(podName);
                for(Entry<String, String> e : env.entrySet()){
                    content.append(e.getKey()).append("=").append(e.getValue()).append("\n");
                }

                // update matched pod
                for(String ns: pods.keySet()) {
                    if(namespace != null && !ns.equals(namespace))
                        continue;

                    if("0".equals(envs.get(podName, "conn_" + ns))){
                        /*
                        * When try to send deleted pod, the thead is wait to connect forever with read-timeout zero(0).
                        * So remove pod of namesapce from a connection registry.
                        * 
                        * Related source codes.
                        * - WebSocketStreamHandler.open()  # this.notifyAll()
                        * - WebSocketStreamHandler$WebSocketOutputStream.write()  # this.wait() when this.socket == null
                        */
                        conns.remove(podName, ns);
                    }

                    log.debug("Update ssh pod env variables. [pod={}, ns={}]\n{}", podName, ns, content);
                    
                    File meta = new File(".env");
                    Process ps = new Exec(client).exec(ns, podName, new String[]{"sh", "-c", "tar xf - -C /"}, true);
                    writeFileAsTar(ps.getOutputStream(), meta, content.toString().getBytes());
                    ps.destroy();
                    log.debug("Finish to write env variables. [pod={}, ns={}]", podName, ns);

                    // close socket manually
                    Object target = ps;
                    String[] paths = new String[]{"streamHandler", "socket"};
                    for(String path : paths){
                        Field field = ReflectionUtils.findField(target.getClass(), path);
                        ReflectionUtils.makeAccessible(field);
                        target = field.get(target);
                    }
                    WebSocket.class.cast(target).close(0, "done");
                    log.debug("Close websocket to update env variables. [pod={}, ns={}]", podName, ns);
                }
            } catch (Exception e) {
                if(e instanceof ApiException){
                    ApiException ae = (ApiException) e;
                    log.info("API Error :: {} - {}", ae.getCode(), ae.getMessage());
                    log.debug("{}", ae.getResponseBody());
                }

                log.debug("", e);
            }
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
     * private ResourceWatcher class
     */
    private class SecretWatcher extends ResourceWatcher<V1Secret>{
        public SecretWatcher(ApiClient client) {
            super(client);
        }

        public Call createWatchCall(CoreV1Api coreV1Api) throws ApiException {
            Call call = coreV1Api.listSecretForAllNamespacesCall(null, null, null, null, null, null, null, Boolean.TRUE, null, null);
            return call;
        }

        public Type watchType(){
            Type watchType = new TypeToken<Watch.Response<V1Secret>>(){}.getType();
            return watchType;
        }

        public void forEach(V1Secret secret, Response<V1Secret> res) throws Exception{
            String name = secret.getMetadata().getName();
            String user = StringUtils.substringBetween(name, "zcp-system-sa-", "-token");
            
            if(user == null || "DELETED".equals(res.type))
                return;
                
            log.debug("'{}' is {}. update token of '{}'.", name, res.type, user);

            String podName = "web-ssh-" + user;
            if(!conns.containsRow(podName))
               return;

            String token = new String(secret.getData().get("token"));
            envs.put(podName, "token", token);
            syncEnv(podName, null);
        }
    }; 

    private class PodWatcher extends ResourceWatcher<V1Pod>{
        public PodWatcher(ApiClient client) {
            super(client);
        }

        public Call createWatchCall(CoreV1Api coreV1Api) throws ApiException {
            String labelSelector = "app=web-ssh";
            Call call = coreV1Api.listPodForAllNamespacesCall(null, null, labelSelector, null, null, null, null, Boolean.TRUE, null, null);
            return call;
        }

        public Type watchType(){
            Type watchType = new TypeToken<Watch.Response<V1Pod>>(){}.getType();
            return watchType;
        }

        public void forEach(V1Pod pod, Response<V1Pod> res) throws Exception {
            String ns = pod.getMetadata().getNamespace();
            String name = pod.getMetadata().getName();
            String status = pod.getStatus().getPhase();

            if(!"DELETE".equals(res.type) && "Succeeded".equals(status)){
                log.info("Delete unused ssh pod({}, ns={})", name, ns);
                manager.deletePod(pod.getMetadata().getNamespace(), name);
            }

            if("Running".equals(status)){
                if(conns.get(name, ns).isEmpty()) {
                    return;
                }

                log.info("Start connecting to ssh pod({}, ns={})", name, ns);
                for(WebSocketSession in : conns.get(name, ns)) {
                    WebSshHandler2 handler = HANDLER.of(in);
                    handler.getRelaySession(in);
                }
            }
        }
    };
}
