# Installation Guide

zcp-iam 은 zcp-portal-ui (Console)의 back-end api server 로서, KeyCloak 과 Kubernetes(이하 k8s) 의 Proxy 역할을 하는 API Server 이다.

zcp-iam 을 설치하기 이전에 k8s cluster 가 설치되어 있어야 하고, cluster-admin role 권한으로 `kubectl` 을 수행 할 수 있는 환경을 갖추어야 한다.

## Clone Project

Clone this project into the desktop

```
$ git clone https://github.com/cnpst/zcp-iam.git
```

## Update Environment Variables

설치 환경에 맞게 `setenv.sh` 파일을 수정한다.

각 정보를 확인하는 자세한 방법은 Appendix를 참고한다.

> `jenkins_token` 값은 Jenkins 설치/설정 이후 값을 확인가능하다. 설정이 누락되지 않도록 주의한다.
> 차후, Jenkins 설치/설정 완료 후 zcp-iam-secret을 변경하여 zcp-iam pod를 다시 올린다.
> (warn) 최초 설치시에 `jenkins_token` 값을 비워두지 않고 dump값을 넣어둔다.

```
$ cd k8s/template

$ vi setenv.sh
#!/bin/bash
out_dir=.tmp

# variables be set as jenkins job properties. use this variables when you install manually.
keycloak_user=cloudzcp-admin
keycloak_pwd=                   #변경
jenkins_user=cloudzcp-admin
jenkins_token=api-token         #변경

sa=zcp-system-admin
domain_prefix=pog-dev-          #변경
api_server=kubernetes.default   #api-server endpoint 정보 변경
namespace=zcp-system
image=registry.au-syd.bluemix.net/cloudzcp/zcp-iam:1.1.0  #확인

replicas=1
...
```

## Install MongoDB

helm chart 를 이용하여 mongodb 를 설치한다.

### for IKS

```
$ ./install_mongodb_iks.sh
...
```

### for EKS

```
$ ./install_mongodb_eks.sh
...
```

## Generate YAML (Kubernetes Resources)

설정된 `setenv.sh` 파일을 바탕으로 `template.sh` 파일을 실행한다.

`template.sh` 파일은 템플릿 파일(`.tpl`/`.tpl2`)을 변환하여 YAML 파일을 생성한다.

`.tmp` 는 `setenv.sh` 파일의 `out_dir` 값과 동일하다.

```
$ bash template.sh

$ ls -l .tmp
-rw-r--r--  1 hoon  staff  1312  3 15 12:27 setenv.sh
-rw-r--r--  1 hoon  staff   608  3 15 12:27 varlables.log
-rw-r--r--  1 hoon  staff   686  3 15 12:28 zcp-iam-config.yaml
-rw-r--r--  1 hoon  staff  2748  3 15 12:27 zcp-iam-deployment.yaml
-rw-r--r--  1 hoon  staff   404  3 15 12:27 zcp-iam-secret.yaml
```

## Create Kubernetes Resource

템플릿을 통해 생성된 YAML 파일을 아래의 명령으로 실행한다.

```
$ kubectl create -f .tmp
configmap/zcp-iam-config created
deployment.apps/zcp-iam created
service/zcp-iam created
secret/zcp-iam-secret created
...

## check to create
$ kubectl get deploy,po,cm,secret,svc -n zcp-system -l component=zcp-iam
NAME                            DESIRED   CURRENT   UP-TO-DATE   AVAILABLE   AGE
deployment.extensions/zcp-iam   1         1         1            1           17d

NAME                          READY     STATUS    RESTARTS   AGE
pod/zcp-iam-68649d9bf-6n8gn   1/1       Running   0          1d

NAME                       DATA      AGE
configmap/zcp-iam-config   10        17d

NAME                    TYPE      DATA      AGE
secret/zcp-iam-secret   Opaque    6         17d

NAME              TYPE        CLUSTER-IP     EXTERNAL-IP   PORT(S)   AGE
service/zcp-iam   ClusterIP   172.21.17.89   <none>        80/TCP    17d
```

## Appendix

### Jenkins 의 api-token 정보를 확인하는 방법

- Jenkins에 로그인 한다. (폴더 생성권한 필요)
- 우측 상단의 사용자 이름을 클릭한다.
- 좌측 메뉴의 설정 페이지로 이동한다.
- API Token > SHOW API TOKEN... 버튼을 클릭하여 값을 확인한다.

### api-server endpoint 정보 확인
```
$ kubectl cluster-info
Kubernetes master is running at https://c4.seo01.containers.cloud.ibm.com:26245
...
```

### ~~KeyCloak 의 master realm client 의 secret 정보를 확인하는 방법~~

```
!) setenv.sh 에서 자동으로 설정되도록 변경됨
```

- KeyCloak 에서 사용하는 Postgresql 에 접속하여 Client 테이블에서 Secret 정보를 Select 한다.

```
$ kubectl exec -it zcp-oidc-postgresql-c94cc488f-znq2v psql
psql (9.6.2)
Type "help" for help.

keycloak=# select secret from client where realm_id = 'master' and client_id = 'master-realm';
                secret
--------------------------------------
 237ef2ad-c5f3-491a-89ab-dab04c8bf268
(1 row)

keycloak=# \q
```

Secret 정보를 복사 한 후 base64로 encoding 한다.

```
$ echo -n "secret of master realm client" | base64
```

`KEYCLOAK_MASTER_CLIENT_SECRET` 의 value 를 base64 encoding 된 값으로 변경한다.

KeyCloak 설치 시 설정한 admin id/password (KEYCLOAK_ADMIN_ID, KEYCLOAK_ADMIN_PWD) 도 base64 encoding 한 후, 각각 `KEYCLOAK_MASTER_USERNAME`, `KEYCLOAK_MASTER_PASSWORD` 의 value 값을 변경한다.

(:white_check_mark: KeyCloak 설치 시 admin id/password 변경하지 않은 경우 그대로 사용하면 됨)
