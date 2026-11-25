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
 static String status="화면 공유 대기";
 final Map<String,String> basics=Map.ofEntries(Map.entry("出口","출구"),Map.entry("入口","입구"),Map.entry("搜索","검색"),Map.entry("酒店","호텔"),Map.entry("地铁","지하철"),Map.entry("公交","버스"),Map.entry("附近","주변"),Map.entry("导航","길안내"),Map.entry("路线","경로"),Map.entry("步行","도보"),Map.entry("卫生间","화장실"),Map.entry("停车场","주차장"));
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
 final Map<Rect,String> current=new LinkedHashMap<>();
 static boolean readableChinese(String key){
  int han=0, latin=0, digits=0;
  for(int cp:key.codePoints().toArray()){
   if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HAN)han++;
   else if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.HANGUL)return false;
   else if(Character.UnicodeScript.of(cp)==Character.UnicodeScript.LATIN)latin++;
   else if(Character.isDigit(cp))digits++;
  }
  return han>0;
 }
 void render(){
  labels.items.clear();
  for(Map.Entry<Rect,String> entry:current.entrySet()){
   String korean=cache.get(entry.getValue());
   if(korean!=null && korean.matches(".*[가-힣].*"))labels.items.put(entry.getKey(),korean);
  }
  labels.invalidate();
 }
 static class Labels extends View {
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  final Map<Rect,String> items=new LinkedHashMap<>();
  int sourceWidth,sourceHeight;
  Labels(Context context){super(context);}
  protected void onDraw(Canvas canvas){
   if(sourceWidth<=0 || sourceHeight<=0)return;
   WindowInsets insets=getRootWindowInsets();
   android.graphics.Insets bars=insets==null?android.graphics.Insets.NONE:insets.getInsets(WindowInsets.Type.systemBars());
   int[] origin=new int[2];getLocationOnScreen(origin);
   float scale=(float)getWidth()/sourceWidth;
   Point full=new Point();getDisplay().getRealSize(full);int fullHeight=full.y;
   float top=Math.abs(fullHeight-sourceHeight*scale-bars.top-bars.bottom)<Math.abs(fullHeight-sourceHeight*scale)?bars.top:0;
   canvas.translate(0,top-origin[1]);canvas.scale(scale,scale);
   ArrayList<Rect> drawn=new ArrayList<>();
   for(Map.Entry<Rect,String> item:items.entrySet()){
    Rect r=item.getKey();
    if(r.width()<8 || r.height()<8 || drawn.stream().anyMatch(other->Rect.intersects(other,r)))continue;
    String value=item.getValue();
    paint.setTextSize(Math.min(44,r.height()*0.86f));
    float width=Math.max(1,paint.measureText(value));
    float fitted=paint.getTextSize()*Math.min(1,(r.width()-3)/width);
    paint.setTextSize(Math.max(fitted, Math.min(r.height()*0.85f,12*getResources().getDisplayMetrics().scaledDensity/scale)));
    while(value.length()>1 && paint.measureText(value)>r.width()-2)value=value.substring(0,value.length()-1);
    canvas.save();canvas.clipRect(r);
    paint.setColor(0xffffffff);canvas.drawRect(r,paint);
    paint.setColor(0xff17232b);
    canvas.drawText(value,r.left+1,r.exactCenterY()-(paint.ascent()+paint.descent())/2,paint);
    canvas.restore();drawn.add(r);
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
   running=true;status="화면 공유 중 · 중국어 인식 대기";
   Rect size=wm.getMaximumWindowMetrics().getBounds();
   resize(size.width(),size.height());
   translator.downloadModelIfNeeded().addOnSuccessListener(v->{ready=true;for(String key:new HashSet<>(current.values()))lookup(key);render();}).addOnFailureListener(e->{status="기본 사전 모드 · 시작 화면에서 번역 모델을 준비하세요";Toast.makeText(this,status,Toast.LENGTH_LONG).show();});
  }catch(RuntimeException e){Toast.makeText(this,"화면 공유를 다시 시작해 주세요.",Toast.LENGTH_LONG).show();stopSelf();}
  return START_NOT_STICKY;
 }
 void resize(int w,int h){
  generation++;clear(); latest=null;
  labels.sourceWidth=w;labels.sourceHeight=h;
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
 void add(Rect box,String key){current.put(new Rect(box),key);lookup(key);}
 void lookup(String key){
  if(cache.get(key)!=null)return;
  String saved=getSharedPreferences("translations",MODE_PRIVATE).getString(key,basics.get(key));
  if(saved!=null){cache.put(key,saved);return;}
  if(!ready || pending.size()>=16 || !pending.add(key))return;
  translator.translate(key).addOnSuccessListener(value->{
   if(stopped)return;
   cache.put(key,value);
   if(value.matches(".*[가-힣].*"))getSharedPreferences("translations",MODE_PRIVATE).edit().putString(key,value).apply();
   if(visible && current.containsValue(key))render();
  }).addOnFailureListener(e->status="기기 내 번역 실패 · 모델 점검 필요").addOnCompleteListener(t->pending.remove(key));
 }
 void clear(){current.clear();if(labels!=null){labels.items.clear();labels.invalidate();}}
 final Runnable next=this::process;
 void process(){
  if(stopped||!visible||busy||latest==null)return;
  long delay=250-(SystemClock.elapsedRealtime()-last);
  if(delay>0){timer.removeCallbacks(next);timer.postDelayed(next,delay);return;}
  Bitmap frame=latest;latest=null;busy=true;last=SystemClock.elapsedRealtime();
  int epoch=generation;
  ocr.process(InputImage.fromBitmap(frame,0)).addOnSuccessListener(text->{
   if(stopped||epoch!=generation||!visible)return;
   current.clear();
   status="인식된 텍스트 블록 "+text.getTextBlocks().size()+"개";
   for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
    String key=line.getText();Rect box=line.getBoundingBox();
    if(box==null)continue;
    if(readableChinese(key) && !key.matches(".*[0-9¥￥%].*"))add(box,key);
    else for(Text.Element element:line.getElements()){
     String part=element.getText();
     if(element.getBoundingBox()!=null && readableChinese(part) && !part.matches(".*[¥￥%].*"))add(element.getBoundingBox(),part);
    }
   }
   render();
  }).addOnFailureListener(e->status="화면 글자 인식 실패").addOnCompleteListener(t->{frame.recycle();busy=false;process();});
 }
 public void onDestroy(){running=false;stopped=true;timer.removeCallbacksAndMessages(null);if(display!=null)display.release();if(frames!=null)frames.close();if(projection!=null)projection.stop();if(labels!=null&&labels.isAttachedToWindow())getSystemService(WindowManager.class).removeView(labels);ocr.close();translator.close();super.onDestroy();}
}
