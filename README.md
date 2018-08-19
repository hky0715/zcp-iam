# The zcp-iam Installation Guide

> zcp-iam 은 zcp-portal-ui (Console)의 back-end api server 로서, KeyCloak 과 Kubernetes(이하 k8s) 의 Proxy 역할을 하는 API Server 이다.
> zcp-iam 을 설치하기 이전에 k8s cluster 가 설치되어 있어야 하고, cluster role 권한으로 `kubectl` 을 수행 할 수 있는 환경을 갖추어야 한다.

## Clone this project into the desktop
```
$ git clone https://github.com/cnpst/zcp-iam.git
```

## Deploy the application
프로젝트 별로 수정해야 하는 파일은 **configmap, ingress, secret** 세 가지이다.

k8s configuration 파일 디렉토리로 이동한다.

```
$ cd zcp-iam/k8s
```

### 1. zcp-iam에서 사용 할 zcp-system-admin 및 Console Admin (cloudzcp-admin) 사용자 용 serviceAccount 을 생성한다.
zcp-system namespace 에 **bluemix container registry** 용 secret - `bluemix-cloudzcp-secret` 이 생성 되어 있어야 한다.

```
$ kubectl create -f zcp-system-admin-sa-crb.yaml
```

다음 명령어로 생성된 secret 을 확인한다.
```
$ kubectl get secret -n zcp-system
```

### 2. ConfigMap을 수정하고 배포한다.
* 프로젝트의 `api-server endpoint` 정보를 변경해야 한다.
`api-server endpoint` 정보 확인
```
$ kubectl config view
apiVersion: v1
clusters:
- cluster:
    certificate-authority-data: REDACTED
    server: https://169.56.69.242:23078
  name: zcp-demo
  ...
  ...
```

* ConfigMap 에 `api-server endpoint` 정보 변경
```
$ cd site/
$ vi zcp-iam-config.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: zcp-iam-config
  namespace: zcp-system
data:
  SPRING_ACTIVE_PROFILE: stage
  KEYCLOAK_MASTER_REALM: master
  KEYCLOAK_MASTER_CLIENTID: master-realm
  KEYCLOAK_SERVER_URL: https://lawai-iam.cloudzcp.io/auth/
  KUBE_APISERVER_URL: https://169.56.69.242:30439
```

* ConfigMap 배포
```
$ kubectl create -f zcp-iam-config.yaml
```
### Secret을 수정하고 배포한다.
* KeyCloak 설치 시 admin crediential 정보와 KeyCloak의 master realm에 있는 master-realm client의 secret 값을 변경해야 한다. 
```
apiVersion: v1
kind: Secret
metadata:
  name: zcp-iam-secret
  namespace: zcp-system
type: Opaque
data:
  KEYCLOAK_MASTER_CLIENT_SECRET: NjcyNDVhOWYtY2JjMy00YmJhLWE2NGYtMTc1MDM3Y2Y3YmI5  
  KEYCLOAK_MASTER_USERNAME: Y2xvdWR6Y3AtYWRtaW4=
  KEYCLOAK_MASTER_PASSWORD: Y2xvdWR6Y3AhMjMk
```

**KeyCloak > master realm > clients > master-realm > credentials** 탭으로 이동하여 Secret 정보를 복사 한 후 base64로 incoding 한다.
[base64 encoding web site](https://www.base64encode.org/) 참고. Mac 에서 base64 로 하는 경우 % 가 붙어서 값이 틀림 주의 요망

`KEYCLOAK_MASTER_CLIENT_SECRET` 의 value 를 base64 incoding 된 값으로 변경한다.

KeyCloak의 admin id/password 도 base64 incoding 한 후, 각각 `KEYCLOAK_MASTER_USERNAME`, `KEYCLOAK_MASTER_PASSWORD` 의 value 값을 벼경한다.
** KeyCloak 설치 시 admin id/password 변경하지 않은 경우 그대로 사용하면 됨 **

* Secret 배포
```
$ kubectl create -f zcp-iam-secret.yaml
```


### deployment를 배포한다.
### ingress 를 수정하고 배포한다.
