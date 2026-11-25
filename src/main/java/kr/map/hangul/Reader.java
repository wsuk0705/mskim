package kr.map.hangul;
import android.app.*;
import android.media.*;
import android.media.projection.*;
import android.hardware.display.*;
import android.content.*;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.nl.translate.*;
import java.util.*;
public class Reader extends Service {
 static boolean running;
 final Handler timer=new Handler(Looper.getMainLooper());
 final TextRecognizer ocr=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
 final Translator translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage("zh").setTargetLanguage("ko").build());
 final android.util.LruCache<String,String> cache=new android.util.LruCache<>(1500);
 final Set<String> pending=new HashSet<>();
 Labels labels;
 boolean stopped;
 MediaProjection projection;
 VirtualDisplay display;
 ImageReader frames;
 boolean ready, busy, visible=true;
 long last;
 Bitmap latest;
 int generation;
 static class Labels extends View {
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  final Map<Rect,String> items=new LinkedHashMap<>();
  Labels(Context context){super(context);}
  protected void onDraw(Canvas canvas){
   int[] origin=new int[2]; getLocationOnScreen(origin);
   canvas.translate(-origin[0],-origin[1]);
   for(Map.Entry<Rect,String> item:items.entrySet()){
    Rect r=item.getKey(); paint.setTextSize(Math.max(13,Math.min(44,r.height())));
    paint.setColor(0xfffafafa); canvas.drawRect(r.left,r.top,r.left+paint.measureText(item.getValue())+4,r.bottom,paint);
    paint.setColor(0xff17232b);canvas.drawText(item.getValue(),r.left+2,r.bottom-2,paint);
   }
  }
 }

 public IBinder onBind(Intent intent){return null;}
 public int onStartCommand(Intent intent,int flags,int id){
  if(intent==null || "stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
  NotificationManager nm=getSystemService(NotificationManager.class);
  nm.createNotificationChannel(new NotificationChannel("translation","화면 번역",NotificationManager.IMPORTANCE_LOW));
  PendingIntent end=PendingIntent.getService(this,0,new Intent(this,Reader.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
  startForeground(1,new Notification.Builder(this,"translation").setSmallIcon(android.R.drawable.ic_menu_view)
    .setContentTitle("지도 한글 화면 번역 중").addAction(0,"종료",end).build());
  if(projection!=null)return START_NOT_STICKY;
  try {
   projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,intent.getParcelableExtra("grant",Intent.class));
   projection.registerCallback(new MediaProjection.Callback(){
    public void onStop(){stopSelf();}
    public void onCapturedContentVisibilityChanged(boolean shown){visible=shown;generation++; clear();if(shown)process();}
    public void onCapturedContentResize(int w,int h){if(display!=null && (w!=frames.getWidth()||h!=frames.getHeight()))resize(w,h);}
   },timer);
   WindowManager wm=getSystemService(WindowManager.class);
   labels=new Labels(this);
   WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
   params.alpha=0.75f;
   wm.addView(labels,params);
   running=true;
   Rect size=wm.getMaximumWindowMetrics().getBounds();
   resize(size.width(),size.height());
   translator.downloadModelIfNeeded().addOnSuccessListener(v->{ready=true;process();}).addOnFailureListener(e->{Toast.makeText(this,"번역 모델 다운로드 실패",Toast.LENGTH_LONG).show();stopSelf();});
  }catch(RuntimeException e){Toast.makeText(this,"화면 공유를 다시 시작해 주세요.",Toast.LENGTH_LONG).show();stopSelf();}
  return START_NOT_STICKY;
 }
 void resize(int w,int h){
  generation++;clear(); latest=null;
  ImageReader previous=frames;
  frames=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
  int dpi=getResources().getDisplayMetrics().densityDpi;
  if(display==null)display=projection.createVirtualDisplay("MapHangul",w,h,dpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,frames.getSurface(),null,timer);
  else {display.resize(w,h,dpi);display.setSurface(frames.getSurface());}
  if(previous!=null)previous.close();
  frames.setOnImageAvailableListener(reader->{
   if(stopped||reader!=frames)return;
   try(Image image=reader.acquireLatestImage()){
    if(image==null||!visible)return;
    if(latest!=null && (busy || SystemClock.elapsedRealtime()-last<220))return;
    if(latest!=null)latest.recycle();
    Image.Plane plane=image.getPlanes()[0];
    int stride=plane.getRowStride()/plane.getPixelStride();
    java.nio.ByteBuffer bytes=plane.getBuffer();
    Bitmap padded=Bitmap.createBitmap(stride,h,Bitmap.Config.ARGB_8888);
    if(bytes.remaining()<padded.getByteCount()){
     java.nio.ByteBuffer full=java.nio.ByteBuffer.allocateDirect(padded.getByteCount());
     full.put(bytes);full.rewind();bytes=full;
    }
    padded.copyPixelsFromBuffer(bytes);
    latest=Bitmap.createBitmap(padded,0,0,w,h);
    if(latest!=padded)padded.recycle();
    process();
   }
  },timer);
 }
 void clear(){if(labels!=null){labels.items.clear();labels.invalidate();}}
 final Runnable next=this::process;
 void process(){
  if(stopped||!visible||!ready||busy||latest==null)return;
  long delay=250-(SystemClock.elapsedRealtime()-last);
  if(delay>0){timer.removeCallbacks(next);timer.postDelayed(next,delay);return;}
  Bitmap frame=latest;latest=null;busy=true;last=SystemClock.elapsedRealtime();
  int epoch=generation;
  ocr.process(InputImage.fromBitmap(frame,0)).addOnSuccessListener(text->{
   if(stopped||epoch!=generation||!visible)return;
   labels.items.clear();
   for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
    String key=line.getText();Rect box=line.getBoundingBox();
    if(box==null||!key.matches(".*[\\p{IsHan}].*"))continue;
    String value=cache.get(key);
    if(value!=null)labels.items.put(box,value);
    else if(pending.size()<16 && pending.add(key))translator.translate(key).addOnSuccessListener(v->{
     cache.put(key,v);if(!stopped&&epoch==generation&&visible){labels.items.put(box,v);labels.invalidate();}
    }).addOnCompleteListener(t->pending.remove(key));
   }
   labels.invalidate();
  }).addOnCompleteListener(t->{frame.recycle();busy=false;process();});
 }
 public void onDestroy(){running=false;stopped=true;timer.removeCallbacksAndMessages(null);if(display!=null)display.release();if(frames!=null)frames.close();if(projection!=null)projection.stop();if(labels!=null&&labels.isAttachedToWindow())getSystemService(WindowManager.class).removeView(labels);ocr.close();translator.close();super.onDestroy();}
}
