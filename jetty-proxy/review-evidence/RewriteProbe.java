import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import dev.example.jettyclient.proxy.*;
import dev.example.jettyclient.proxy.policies.*;
public class RewriteProbe {
 static final int SIZE=8*1024*1024, CHUNK=65536;
 public static void main(String[] ignored) throws Exception {
  var settings=new LinkRewriteSettings(URI.create("http://backend/base"),URI.create("https://frontend/portal"),SIZE,12L*1024*1024);
  var policy=new LinkRewritePolicy(settings);
  var request=new ProxyRequestMetadata("GET",URI.create("http://backend/base/style.css"),Map.of());
  var response=new ProxyResponseMetadata(200,Map.of("Content-Type",List.of("text/css; charset=UTF-8")));
  var selection=policy.select(request,response);
  for(int links:new int[]{0,40,10000}) {
   String prefix="a{background:url(http://backend/base/x)}\n".repeat(links);
   String text=prefix+" ".repeat(SIZE-prefix.length());
   var parts=new ArrayList<ByteBuffer>();
   byte[] input=text.getBytes(StandardCharsets.UTF_8);
   for(int offset=0;offset<SIZE;offset+=CHUNK){var b=ByteBuffer.allocateDirect(CHUNK);b.put(input,offset,CHUNK).flip();parts.add(b);}
   var body=ProxyResponseBody.of(parts);
   ProxyResponseBody output=selection.rewriter().rewrite(response,body);
   MessageDigest actual=MessageDigest.getInstance("SHA-256");long calls=0, tiny=0;
   for(var b:output.buffers()){calls+=(b.remaining()+CHUNK-1)/CHUNK;if(b.remaining()<1024)tiny++;actual.update(b);}
   var expected=MessageDigest.getInstance("SHA-256").digest(text.replace("http://backend/base/","https://frontend/portal/").getBytes(StandardCharsets.UTF_8));
   if(!Arrays.equals(expected,actual.digest()))throw new AssertionError("wrong output");
   System.out.printf(Locale.ROOT,"{\"links\":%d,\"inputBytes\":%d,\"inputSegments\":%d,\"outputBytes\":%d,\"outputSegments\":%d,\"writerCallsFromEngineAlgorithm\":%d,\"segmentsBelow1KiB\":%d,\"sha256Matches\":true}%n",links,SIZE,body.bufferCount(),output.length(),output.bufferCount(),calls,tiny);
  }
 }
}
