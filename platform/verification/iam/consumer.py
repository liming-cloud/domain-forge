"""独立消费验证夹具，候选制品只来自本轮隔离file仓库。"""
from pathlib import Path
import importlib.util,json,os,shutil,subprocess,tempfile,zipfile,hashlib
import argparse
parser=argparse.ArgumentParser(description="从已发布制品生成独立IAM消费项目，不使用IAM私有资源")
for name in ['code','publication','repository','maven','output']:parser.add_argument('--'+name,type=Path,required=True)
args=parser.parse_args();code=args.code.resolve();art=args.output.resolve();art.mkdir(parents=True,exist_ok=True)
publication=json.loads(args.publication.read_text());workspace=Path(tempfile.mkdtemp(prefix='domain-forge-iam-consumer-')).resolve()
spec=importlib.util.spec_from_file_location('iam_template',code/'templates/ddd-service/generate.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
app=workspace/'identity-consumer';generated=module.generate(code/'templates/ddd-service',app,'example.company','identity-consumer','example.company.consumer')
parent=workspace/'company-parent';parent.mkdir();(parent/'pom.xml').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>example.company</groupId><artifactId>company-parent</artifactId><version>1.0.0</version><packaging>pom</packaging></project>')
pom=app/'pom.xml';s=pom.read_text().replace('<modelVersion>4.0.0</modelVersion>','<modelVersion>4.0.0</modelVersion><parent><groupId>example.company</groupId><artifactId>company-parent</artifactId><version>1.0.0</version><relativePath>../company-parent/pom.xml</relativePath></parent>')
new=''
for group,artifact in [('org.domainforge','domain-forge-iam-client'),('org.domainforge','domain-forge-access-control-backend'),('org.domainforge','domain-forge-access-control-contract'),('cn.dev33','sa-token-core'),('org.springframework','spring-web'),('org.springframework.cloud','spring-cloud-commons'),('org.springframework.cloud','spring-cloud-loadbalancer'),('io.projectreactor','reactor-core')]:
 new+=f'<dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId></dependency>\n'
position=s.index('\n  <dependencies>')+len('\n  <dependencies>');s=s[:position]+new+s[position:];pom.write_text(s)
bootstrap=app/'src/main/java/example/company/consumer/bootstrap/Application.java';s=bootstrap.read_text().replace('import org.springframework.boot.SpringApplication;','import org.springframework.boot.SpringApplication;\nimport org.domainforge.access.backend.configuration.BackendAccessConfiguration;\nimport org.domainforge.iam.client.configuration.IamClientConfiguration;').replace('@Import(DomainForgePersistenceConfiguration.class)','@Import({DomainForgePersistenceConfiguration.class, IamClientConfiguration.class, BackendAccessConfiguration.class})');bootstrap.write_text(s)
p=app/'src/main/java/example/company/consumer/interfaces/http/IdentityEndpoint.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('''package example.company.consumer.interfaces.http;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckLogin;
import org.domainforge.access.api.CurrentAccess;
import org.domainforge.access.api.CurrentSubject;
import org.domainforge.web.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 独立消费夹具只读取真实当前主体，不增加示例业务模型。 */
@RestController
public final class IdentityEndpoint {
  private final CurrentAccess access;
  /** 组件接口不携带IAM数据库或Redis资源。 */
  public IdentityEndpoint(CurrentAccess access) { this.access=access; }
  /** 成功路径必须实际获得当前主体，不以统一拒绝证明权限正确。 */
  @SaCheckPermission("iam:account:read") @GetMapping("/verification/read")
  public ApiResponse<CurrentSubject> read() { return ApiResponse.success(access.current()); }
  /** 登录但无管理权限仍可读取自身身份。 */
  @SaCheckLogin @GetMapping("/verification/me")
  public ApiResponse<CurrentSubject> me() { return ApiResponse.success(access.current()); }
  /** 故意遗漏策略的真实反例，必须在原生入口默认拒绝。 */
  @GetMapping("/verification/undeclared")
  public ApiResponse<String> undeclared() { return ApiResponse.success("must-not-reach"); }
}
''')
probe=app/'src/main/java/example/company/consumer/infrastructure/verification/NativeDiscoveryProbe.java';probe.parent.mkdir(parents=True,exist_ok=True);probe.write_text('''package example.company.consumer.infrastructure.verification;

import cn.dev33.satoken.annotation.SaCheckPermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.domainforge.web.api.ApiResponse;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅独立验证工程使用的原生发现观测，不进入平台服务或可分发模板。 */
@RestController
public final class NativeDiscoveryProbe {
  private final LoadBalancerClientFactory clients;
  /** 从实际IAM子上下文取得同一个原生候选供应器，不用SDK视图代替路由事实。 */
  public NativeDiscoveryProbe(LoadBalancerClientFactory clients) { this.clients = clients; }
  /** 管理权限保护的技术探针；只声明实际原生供应器本次发出的候选列表。 */
  @SaCheckPermission("iam:account:read") @GetMapping("/verification/discovery")
  public ApiResponse<List<Map<String, Object>>> instances() {
    var supplier = clients.getInstance("iam", ServiceInstanceListSupplier.class);
    if (supplier == null) throw new IllegalStateException("IAM native supplier absent");
    var instances = supplier.get().next().block(Duration.ofMillis(500));
    if (instances == null) throw new IllegalStateException("IAM native supplier emitted no result");
    return ApiResponse.success(instances.stream().map(instance ->
        Map.<String, Object>of("host", instance.getHost(), "port", instance.getPort(),
            "secure", instance.isSecure())).toList());
  }
}
''')
props=app/'src/main/resources/application.properties';props.write_text(props.read_text()+'''
domainforge.iam-client.service-id=iam
domainforge.iam-client.project-id=project-alpha
domainforge.iam-client.ssl-bundle=iam-client
spring.ssl.bundle.jks.iam-client.keystore.location=${CONSUMER_CLIENT_STORE}
spring.ssl.bundle.jks.iam-client.keystore.password=${CONSUMER_STORE_PASSWORD}
spring.ssl.bundle.jks.iam-client.keystore.type=PKCS12
spring.ssl.bundle.jks.iam-client.truststore.location=${CONSUMER_TRUST_STORE}
spring.ssl.bundle.jks.iam-client.truststore.password=${CONSUMER_STORE_PASSWORD}
spring.ssl.bundle.jks.iam-client.truststore.type=PKCS12
spring.cloud.nacos.discovery.server-addr=${NACOS_SERVER_ADDR}
spring.cloud.nacos.discovery.username=${NACOS_USERNAME}
spring.cloud.nacos.discovery.password=${NACOS_PASSWORD}
spring.cloud.nacos.discovery.namespace=${NACOS_NAMESPACE}
spring.cloud.nacos.discovery.group=${NACOS_GROUP}
spring.cloud.nacos.discovery.register-enabled=false
# 此独立验证应用复用Nacos自身实例缓存，明确关闭重复的LoadBalancer缓存。
spring.cloud.loadbalancer.cache.enabled=false
server.address=127.0.0.1
server.ssl.enabled=true
server.ssl.key-store=${CONSUMER_SERVER_STORE}
server.ssl.key-store-password=${CONSUMER_STORE_PASSWORD}
server.ssl.key-store-type=PKCS12
sa-token.is-print=false
sa-token.is-log=false
''')
settings=workspace/'settings.xml';settings.write_text('<settings><mirrors><mirror><id>product-central</id><mirrorOf>external:*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors><profiles><profile><id>iam-verification</id><repositories><repository><id>iam-verification</id><url>'+Path(publication['repository']).as_uri()+'</url><releases><enabled>true</enabled><checksumPolicy>fail</checksumPolicy></releases><snapshots><enabled>false</enabled></snapshots></repository></repositories></profile></profiles><activeProfiles><activeProfile>iam-verification</activeProfile></activeProfiles></settings>')
cache=workspace/'repository'
# 只复用外部依赖缓存，平台坐标在独立消费前必须为空；不install仓库源码。
def ignore(path,names):return ['domainforge'] if Path(path).name=='org' and 'domainforge' in names else []
shutil.copytree(args.repository,cache,ignore=ignore)
assert not (cache/'org/domainforge').exists()
env=dict(os.environ)
with (art/'consumer-build.log').open('w') as log:
 result=subprocess.run([str(args.maven.resolve()),'-B','-ntp','-s',str(settings),'-Dmaven.repo.local='+str(cache),'verify'],cwd=app,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600)
report={'ok':result.returncode==0,'workspace':str(workspace),'project':str(app),'cache':str(cache),'settings':str(settings),'generated':generated,'publication':publication['repository'],'log':'consumer-build.log'}
if result.returncode==0:
 jar=app/'target/identity-consumer-0.1.0.jar';report['artifact']=str(jar);report['sha256']=hashlib.sha256(jar.read_bytes()).hexdigest()
 with zipfile.ZipFile(jar) as z:
  libraries=[name for name in z.namelist() if name.startswith('BOOT-INF/lib/')]
  assert not any(any(term in name for term in ['sa-token-redis','spring-data-redis','lettuce','/iam-']) for name in libraries)
  report['platform_libraries']=[name for name in libraries if 'domain-forge-' in name]
 assert {p.name for p in (cache/'org/domainforge').iterdir()}=={'domain-forge-bom','domain-forge-domain','domain-forge-web-support','domain-forge-persistence','domain-forge-access-control-contract','domain-forge-access-control-backend','domain-forge-iam-client'}
(art/'consumer-build.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({'ok':report['ok'],'workspace':str(workspace)}));raise SystemExit(result.returncode)
