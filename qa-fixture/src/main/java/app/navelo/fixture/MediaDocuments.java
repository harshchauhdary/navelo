package app.navelo.fixture;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.*;

public class MediaDocuments extends DocumentsProvider {
    private static final String[] COLS = {"document_id","_display_name","mime_type","_size","last_modified","flags"};
    private File video, episode, subtitle;
    @Override public boolean onCreate() {
        video = new File(getContext().getFilesDir(),"sample.mp4");
        episode = new File(getContext().getFilesDir(),"episode.mp4");
        subtitle = new File(getContext().getFilesDir(),"sample.en.srt");
        try {
            if (!video.exists()) try(InputStream in=getContext().getAssets().open("sample.mp4"); OutputStream out=new FileOutputStream(video)) {
                byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            if (!episode.exists()) try(InputStream in=getContext().getAssets().open("sample.mp4"); OutputStream out=new FileOutputStream(episode)) {
                byte[] buffer=new byte[8192]; int n; while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            }
            try(FileOutputStream out=new FileOutputStream(subtitle)) { out.write("1\n00:00:00,000 --> 00:01:00,000\nNavelo external subtitles are working.\n".getBytes("UTF-8")); }
        } catch(IOException e) { throw new IllegalStateException(e); }
        return true;
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns=projection!=null?projection:new String[]{"root_id","document_id","title","flags","available_bytes"};
        MatrixCursor c=new MatrixCursor(columns); MatrixCursor.RowBuilder r=c.newRow();
        for(String col:columns) switch(col) {
            case "root_id":r.add(col,"fixture");break;case "document_id":r.add(col,"media");break;
            case "title":r.add(col,"Navelo test media");break;case "flags":r.add(col,DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);break;
            case "available_bytes":r.add(col,0L);break;default:r.add(col,null);
        }
        return c;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(projection!=null?projection:COLS); add(c,id);return c;
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) throws FileNotFoundException {
        MatrixCursor c=new MatrixCursor(projection!=null?projection:COLS);
        if(parent.equals("media")){add(c,"movie");add(c,"subtitle");add(c,"show");}
        else if(parent.equals("show")){add(c,"ep1");add(c,"ep2");}
        return c;
    }
    @Override public boolean isChildDocument(String parent,String child) {
        return parent.equals("media") || (parent.equals("show") && child.startsWith("ep"));
    }
    private void add(MatrixCursor cursor,String id) throws FileNotFoundException {
        boolean dir=id.equals("media")||id.equals("show");
        String name;
        switch(id) {
            case "media":name="Navelo test media";break;case "show":name="Little Journeys";break;
            case "movie":name="City.Lights.2026.mp4";break;case "subtitle":name="City.Lights.2026.en.srt";break;
            case "ep1":name="Little.Journeys.S01E01.mp4";break;case "ep2":name="Little.Journeys.S02E01.mp4";break;
            default:throw new FileNotFoundException();
        }
        MatrixCursor.RowBuilder row=cursor.newRow();
        for(String col:cursor.getColumnNames())switch(col){
            case "document_id":row.add(col,id);break;case "_display_name":row.add(col,name);break;
            case "mime_type":row.add(col,dir?DocumentsContract.Document.MIME_TYPE_DIR:id.equals("subtitle")?"application/x-subrip":"video/mp4");break;
            case "_size":row.add(col,dir?0L:id.equals("subtitle")?subtitle.length():id.startsWith("ep")?episode.length():video.length());break;
            case "last_modified":row.add(col,1_790_000_000_000L);break;case "flags":row.add(col,0);break;
            default:row.add(col,null);
        }
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException();
        if(id.equals("movie"))return ParcelFileDescriptor.open(video,ParcelFileDescriptor.MODE_READ_ONLY);
        if(id.equals("ep1")||id.equals("ep2"))return ParcelFileDescriptor.open(episode,ParcelFileDescriptor.MODE_READ_ONLY);
        if(id.equals("subtitle"))return ParcelFileDescriptor.open(subtitle,ParcelFileDescriptor.MODE_READ_ONLY);
        throw new FileNotFoundException();
    }
}
