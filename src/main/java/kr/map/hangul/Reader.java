package kr.map.hangul;
import android.accessibilityservice.AccessibilityService;
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
public class Reader extends AccessibilityService {
 final Handler timer=new Handler(Looper.getMainLooper());
 final TextRecognizer ocr=TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
 final Translator translator=Translation.getClient(new TranslatorOptions.Builder().setSourceLanguage("zh").setTargetLanguage("ko").build());
 final android.util.LruCache<String,String> cache=new android.util.LruCache<>(1500);
 final Set<String> pending=new HashSet<>();
 Labels labels;
 boolean stopped;
 int window=-1;
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
 protected void onServiceConnected(){
  labels=new Labels(this);
  WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
  getSystemService(WindowManager.class).addView(labels,p);
  translator.downloadModelIfNeeded().addOnSuccessListener(v->tick()).addOnFailureListener(e->Toast.makeText(this,"���� �� �ٿ�ε� ����. ���ͳ� ���� �� ���񽺸� �ٽ� �Ѽ���.",Toast.LENGTH_LONG).show());
 }
 int active(){AccessibilityNodeInfo root=getRootInActiveWindow();return root!=null && "com.baidu.BaiduMap".contentEquals(String.valueOf(root.getPackageName()))?root.getWindowId():-1;}
 void later(){if(!stopped)timer.postDelayed(this::tick,350);}
 void clear(){if(labels!=null){labels.items.clear();labels.invalidate();}}
 public void onAccessibilityEvent(AccessibilityEvent event){if(active()!=window)clear();}
 public void onInterrupt(){clear();}
 void tick(){
  if(stopped)return;
  window=active(); final int id=window;
  if(id<0){clear();later();return;}
  takeScreenshotOfWindow(id,getMainExecutor(),new TakeScreenshotCallback(){
   public void onFailure(int error){clear();later();}
   public void onSuccess(ScreenshotResult result){
    Bitmap bitmap;
    try(HardwareBuffer buffer=result.getHardwareBuffer()){
     Bitmap hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());
     if(hardware==null){later();return;}
     bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();
    }
    if(stopped){bitmap.recycle();return;}
    ocr.process(InputImage.fromBitmap(bitmap,0)).addOnSuccessListener(text->{
     if(stopped||active()!=id)return;
     labels.items.clear();
     for(Text.TextBlock block:text.getTextBlocks())for(Text.Line line:block.getLines()){
      String key=line.getText();Rect rect=line.getBoundingBox();
      if(rect==null||!key.matches(".*[\\p{IsHan}].*"))continue;
      String value=cache.get(key);
      if(value!=null)labels.items.put(rect,value);
      else if(pending.size()<16 && pending.add(key))translator.translate(key).addOnSuccessListener(v->cache.put(key,v)).addOnCompleteListener(t->pending.remove(key));
     }
     labels.invalidate();
    }).addOnFailureListener(e->clear()).addOnCompleteListener(t->{bitmap.recycle();later();});
   }
  });
 }
 public void onDestroy(){stopped=true;timer.removeCallbacksAndMessages(null);if(labels!=null)getSystemService(WindowManager.class).removeView(labels);ocr.close();translator.close();super.onDestroy();}
}
