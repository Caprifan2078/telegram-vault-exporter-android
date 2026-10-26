package org.vault.exporter;
import java.io.IOException;
import java.util.Arrays;
/** Validates TDLib descending history pages; short pages are not EOF. */
public final class HistoryCursor {
  private HistoryCursor() {}
  public static final class Selection {
    public final int[] indices;
    public final long nextCursor;
    public final boolean emptyResponse, stalled;
    private Selection(int[] indices,long nextCursor,boolean emptyResponse,boolean stalled){
      this.indices=indices;this.nextCursor=nextCursor;
      this.emptyResponse=emptyResponse;this.stalled=stalled;
    }
  }
  public static Selection select(long savedCursor,long[] responseIds)throws IOException{
    if(savedCursor<0 || responseIds==null || responseIds.length>100)
      throw new IOException("Недопустимая страница TDLib");
    long previous=Long.MAX_VALUE;
    int[] accepted=new int[responseIds.length];int count=0;
    long lowest=Long.MAX_VALUE;
    for(int i=0;i<responseIds.length;i++){
      long id=responseIds[i];
      if(id<=0 || id>=previous)
        throw new IOException("TDLib вернула некорректный порядок или ID сообщений");
      previous=id;
      if(savedCursor>0 && id>=savedCursor)continue;
      accepted[count++]=i;
      lowest=Math.min(lowest,id);
    }
    return new Selection(Arrays.copyOf(accepted,count),
      count>0?lowest:savedCursor,responseIds.length==0,responseIds.length>0&&count==0);
  }
}
