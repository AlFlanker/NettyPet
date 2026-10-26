import dev.example.jettyclient.proxy.*;
import dev.example.jettyclient.proxy.policies.*;
import org.eclipse.jetty.client.HttpClient;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import java.net.URI;
import java.util.*;
public class IntegrationProbe {
 @Configuration(proxyBeanMethods=false)
 @Import(JettyProxyClientConfiguration.class)
 static class Host {
  @Bean HttpClient hostUpstreamClient(){return new HttpClient();}
  @Bean ExistingConsumer existingConsumer(HttpClient client){return new ExistingConsumer(client);}
 }
 record ExistingConsumer(HttpClient client){}
 public static void main(String[] args) throws Exception {
  boolean ambiguous=false;
  try(var context=new AnnotationConfigApplicationContext()) {
   context.register(Host.class);
   try{context.refresh();}
   catch(Exception e){for(Throwable x=e;x!=null;x=x.getCause())if(x instanceof NoUniqueBeanDefinitionException)ambiguous=true;}
  }
  if(!ambiguous)throw new AssertionError("Expected current import ambiguity");
  URI uri=URI.create("http://backend/skin.wcs");
  String method=new HttpClient().newRequest(uri).method("head").getMethod();
  String factoryMethod;
  var client=JettyProxyClientFactory.createStarted(JettyProxyProperties.defaults());
  try{factoryMethod=client.newRequest(uri).method("head").getMethod();}finally{client.stop();}
  if(!method.equals("HEAD")||!factoryMethod.equals("head"))throw new AssertionError("Unexpected method behavior");
  var policy=new LinkRewritePolicy(new LinkRewriteSettings(URI.create("http://backend"),URI.create("https://portal"),1024,2048));
  var request=new ProxyRequestMetadata("GET",uri,Map.of());
  boolean skipped=policy.select(request,new ProxyResponseMetadata(200,Map.of("Content-Type",List.of("application/octet-stream"))))==null;
  if(!skipped)throw new AssertionError("Expected current WCS MIME-only skip");
  System.out.println("{\"existingUnqualifiedHttpClientConsumerFailsAfterImport\":true,\"customClientHeadMethod\":\""+method+"\",\"factoryClientHeadMethod\":\""+factoryMethod+"\",\"wcsOctetStreamRewriteSkipped\":true}");
 }
}
