import org.vault.exporter.HistoryCursor;
import java.util.*;
public class RunHistoryCursorHardeningTests {
 static int checks;
 static void ok(boolean a,String msg){checks++;if(!a)throw new AssertionError(msg);}
 static void expectBad(long saved,long[] ids) throws Exception {
   boolean fails=false;try {HistoryCursor.select(saved,ids);}catch(java.io.IOException err){fails=true;}
   ok(fails,"invalid page accepted "+Arrays.toString(ids));
 }
 public static void main(String[] args) throws Exception {
   long[] ids=new long[100_000];long next=3_000_000;
   Random random=new Random(45321);
   for(int i=0;i<ids.length;i++){next-=1+random.nextInt(19);ids[i]=next;}
   Set<Long> staged=new HashSet<>();long cursor=0;int pages=0;
   while(staged.size()<ids.length){
     int index=0;while(index<ids.length&&ids[index]>cursor&&cursor>0)index++;
     int count=2+(pages%98); // real TDLib can return short pages
     long[] response=Arrays.copyOfRange(ids,index,Math.min(ids.length,index+count));
     HistoryCursor.Selection select=HistoryCursor.select(cursor,response);
     ok(!select.stalled && select.indices.length>0,"history stopped early");
     for(int i:select.indices)ok(staged.add(response[i]),"duplicate");
     cursor=select.nextCursor;
     ok(++pages<100_001,"pagination loop");
   }
   for(long id:ids)ok(staged.contains(id),"lost message "+id);
   ok(staged.size()==100_000,"incomplete result");
   ok(HistoryCursor.select(100,new long[]{100}).stalled,"stall detection");
   ok(HistoryCursor.select(100,new long[]{}).emptyResponse,"EOF classification");
   expectBad(0,new long[]{0});expectBad(0,new long[]{100,100});
   expectBad(0,new long[]{10,11});expectBad(0,new long[]{5,-1});
   expectBad(-1,new long[]{10});expectBad(0,new long[101]);
   System.out.println("PASS "+checks+" checks: 100000 sparse IDs, "+pages+" short pages, duplicates/orders rejected");
 }
}
