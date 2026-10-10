// SPDX-License-Identifier: GPL-3.0-or-later
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.util.PathParser;
import com.android.layoutlib.bridge.Bridge;
import com.android.ide.common.rendering.api.ILayoutLog;
import com.weather.obviouslyweather.widget.ForecastArtwork;
import com.weather.obviouslyweather.widget.WidgetRenderSize;
import com.weather.obviouslyweather.widget.WidgetFonts;
import com.weather.obviouslyweather.widget.FontIdentity;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Pixel checks of the production forecast painter using Studio's native Android graphics, no emulator. */
public class NativeForecastCheck {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    static int assertions;
    static final String SAMPLES = System.getProperty("widget.samples", "design/widget-validation") + "/";
    static Typeface regular, medium;
    static final String[] CONDITIONS = {"clear_day", "clear_night", "partly_cloudy_day", "partly_cloudy_night",
        "overcast", "overcast_rain", "overcast_snow", "thunderstorms", "mist", "not_available"};
    static void require(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String data = System.getProperty("widget.layoutlib.data");
        Bridge bridge = new Bridge();
        ILayoutLog log = new ILayoutLog() {
            public void error(String tag, String message, Throwable error, Object data, Object cookie) {
                System.err.println(message); if (error != null) error.printStackTrace();
            }
        };
        if (!bridge.init(Map.of("ro.build.version.sdk", "37"), new File(data + "/fonts"), data + "/win/lib64",
                data + "/icu/icudt78l.dat", data + "/hyphen-data", new String[0], Map.of(), log))
            throw new IllegalStateException("Android graphics initialization failed");
        byte[] fontBytes = Files.readAllBytes(new File(System.getProperty("widget.font", "app/src/main/res/font/widget_sans.ttf")).toPath());
        java.nio.ByteBuffer fontBuffer = java.nio.ByteBuffer.allocateDirect(fontBytes.length);
        fontBuffer.put(fontBytes); fontBuffer.flip();
        FontIdentity identity = FontIdentity.Companion.read(fontBuffer, 0);
        require(identity != null, "Selected font family metadata is unreadable");
        java.lang.reflect.Method factory = WidgetFonts.class.getDeclaredMethod("typeface", java.nio.ByteBuffer.class, int.class);
        factory.setAccessible(true);
        Typeface font = (Typeface) factory.invoke(WidgetFonts.INSTANCE, fontBuffer, 0);
        regular = Typeface.create(font, 400, false); medium = Typeface.create(font, 500, false);
        checkBundledFont();
        float[][] sizes = {{561,564,3,42}, {420,414,2.5f,35}, {612,640,3,52.5f}, {348,276,3,42}};
        if(Boolean.getBoolean("weather.widget.clockDiagnostics")) {
            for(float[] size:sizes)for(int color:new int[]{Color.WHITE,Color.BLACK})for(boolean rtl:new boolean[]{false,true}) {
                checkClockColumns(size,color,rtl,true,true);
                checkClockColumns(size,color,rtl,true,false);
                checkClockColumns(size,color,rtl,false,false);
                checkClockColumns(size,color,rtl,true,true,false);
                checkHiddenPeriodArtwork(size,color,rtl);
            }
            bridge.dispose();return;
        }
        for (float[] size : sizes) for (String condition : CONDITIONS) for (int color : new int[]{Color.WHITE, Color.BLACK})
            for (boolean rtl : new boolean[]{false, true}) for (boolean daily : new boolean[]{false, true}) {
                int width=(int)size[0], height=(int)size[1]; float density=size[2], textSize=size[3];
                List<ForecastArtwork.Row> rows = rows(condition, daily, color);
                for (ForecastArtwork.Row row : rows) row.getDrop().setTint(color);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                List<ForecastArtwork.Cell> cells = ForecastArtwork.draw(new Canvas(bitmap), width, height, density,
                    textSize, regular, medium, color, rows, rtl);
                Paint temperaturePaint = new Paint(); temperaturePaint.setTypeface(medium); temperaturePaint.setTextSize(textSize*15/14);
                temperaturePaint.setFontVariationSettings("'wght' 500");temperaturePaint.setFontFeatureSettings("tnum");
                float valueWidth=0;for (ForecastArtwork.Row row : rows) valueWidth=Math.max(valueWidth,temperaturePaint.measureText(row.getValue()));
                for (int i=0;i<cells.size();i++) {
                    ForecastArtwork.Cell cell=cells.get(i);
                    Rect ink = opaqueBounds(bitmap, cell.getIconRegion());
                    require(!ink.isEmpty(), "Missing artwork: " + condition);
                    PointF visual=visualCenter(bitmap,cell.getIconRegion(),color);
                    float dx=Math.abs(visual.x-cell.getIconCenterX());
                    float dy=Math.abs(visual.y-cell.getCenterY());
                    require(dx <= 1.1f, condition + " horizontal pixels displaced by " + dx + " at " + width + ", daily="+daily);
                    require(dy <= 1.1f, condition + " vertical pixels displaced by " + dy + " at " + width + ", daily="+daily);
                    require(ink.height() >= 5, "Artwork collapsed: " + condition);
                    RectF valueRegion=rtl?new RectF(0,cell.getIconRegion().top,valueWidth,cell.getIconRegion().bottom):
                        new RectF(width-valueWidth,cell.getIconRegion().top,width,cell.getIconRegion().bottom);
                    Rect valueInk=opaqueBounds(bitmap,valueRegion);
                    require(!valueInk.isEmpty(), "Missing temperature pixels");
                    require(Math.abs(valueInk.exactCenterY()-visual.y) <= 1.1f,
                        "Actual number/icon pixels vertically disagree: "+condition+" at "+width);
                    if(daily) {
                        RectF dayRegion=cell.getIconRegion();
                        float middle=rtl?dayRegion.left:dayRegion.right;
                        RectF nightRegion=rtl?
                            new RectF(middle-dayRegion.width(),dayRegion.top,middle,dayRegion.bottom):
                            new RectF(middle,dayRegion.top,middle+dayRegion.width(),dayRegion.bottom);
                        Rect nightInk=opaqueBounds(bitmap,nightRegion);
                        require(!nightInk.isEmpty(), "Missing night artwork: "+condition+", row="+i);
                        PointF nightCenter=visualCenter(bitmap,nightRegion,color);
                        require(Math.abs(nightCenter.x-(2*middle-cell.getIconCenterX())) <= 1.1f,
                            "Night artwork horizontally displaced: "+condition+", row="+i);
                        require(Math.abs(nightCenter.y-cell.getCenterY()) <= 1.1f,
                            "Night artwork vertically displaced: "+condition+", row="+i);
                    }
                    if(!daily) {
                        Paint chancePaint=new Paint();chancePaint.setTypeface(regular);chancePaint.setTextSize(textSize*13/14);
                        chancePaint.setFontVariationSettings("'wght' 400");chancePaint.setFontFeatureSettings("tnum");
                        float chanceWidth=chancePaint.measureText("0%");
                        float rainEnd=rtl?cell.getIconRegion().right+5*density:cell.getIconRegion().left-5*density;
                        RectF chanceRegion=rtl?new RectF(rainEnd,cell.getIconRegion().top,rainEnd+chanceWidth+1,cell.getIconRegion().bottom):
                            new RectF(rainEnd-chanceWidth-1,cell.getIconRegion().top,rainEnd+1,cell.getIconRegion().bottom);
                        Rect chanceInk=opaqueBounds(bitmap,chanceRegion);
                        require(!chanceInk.isEmpty(), "Missing rain probability pixels");
                        RectF dropRegion=rtl?
                            new RectF(chanceInk.right+2*density,cell.getIconRegion().top,
                                chanceInk.right+16*density,cell.getIconRegion().bottom):
                            new RectF(chanceInk.left-16*density,cell.getIconRegion().top,
                                chanceInk.left-2*density,cell.getIconRegion().bottom);
                        checkRainFigurePixels(bitmap,cell,chanceInk,valueInk,dropRegion,color,density,textSize,
                            rows.get(i).getChance(),rows.get(i).getValue(),
                            condition+" at "+width+", row="+i+", rtl="+rtl+", AM/PM");
                        float actualGapCenter=rtl?(valueInk.right+chanceInk.left)/2f:(chanceInk.right+valueInk.left)/2f;
                        require(Math.abs(visual.x-actualGapCenter) <= 1.1f,
                            "Icon pixels are not horizontally midway between neighboring glyphs: "+condition+" at "+width+
                            ", row="+i+", rtl="+rtl+", icon="+ink+", chance="+chanceInk+", value="+valueInk+", ideal="+actualGapCenter);
                    }
                }
                bitmap.recycle();
            }
        for (float[] size : sizes) for (int color : new int[]{Color.WHITE, Color.BLACK})
            for (boolean rtl : new boolean[]{false, true}) {
                checkClockColumns(size, color, rtl, true, true);
                checkClockColumns(size, color, rtl, true, false);
                checkClockColumns(size, color, rtl, false, false);
                checkClockColumns(size, color, rtl, true, true, false);
                checkHiddenPeriodArtwork(size, color, rtl);
            }
        checkWeatherSizes(); checkMoonPalette(); checkIconContrast(); checkPublishedResolution();
        exportSample(false); exportSample(true);
        exportMixedSample(false, true); exportMixedSample(false, false); exportMixedSample(true, false);
        exportMixedSample(false, true, false);
        exportCurrentIcons();
        System.out.println("PASS: " + assertions + " rendered-pixel checks using selected widget font across 10 conditions, 4 sizes, enlarged fonts, both modes/colors/directions, and native published bitmap dimensions.");
        bridge.dispose();
    }
    static VectorFile weatherVector(String condition,int color) throws Exception {
        return new VectorFile("app/src/main/res/drawable/widget_"+condition+(color==Color.BLACK?"_dark":"")+".xml");
    }
    static void checkBundledFont() throws Exception {
        byte[] bytes=Files.readAllBytes(new File(System.getProperty("widget.font", "app/src/main/res/font/widget_sans.ttf")).toPath());
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        require(System.getProperty("widget.font") != null || hash.equals("29160a80ff49ddcab2c97711247e08b1fab27a484a329ce8b813d820dc559031"),
            "Widget validation is not using the verified Inter font");
        require(regular.getWeight()==400&&medium.getWeight()==500,
            "Widget font lost its regular and medium weight variants");
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(regular);paint.setTextSize(42);
        paint.setFontVariationSettings("'wght' 400");paint.setFontFeatureSettings("tnum");
        float minimum=Float.MAX_VALUE,maximum=0;
        for(char digit='0';digit<='9';digit++) {
            float width=paint.measureText(String.valueOf(digit));minimum=Math.min(minimum,width);maximum=Math.max(maximum,width);
        }
        require(maximum-minimum<=0.01f,"Widget tabular numerals do not share consistent clock advances");
        for(String glyph:new String[]{"°","↑","↓","К","и","ї","в"})
            require(paint.hasGlyph(glyph),"Bundled widget font cannot render required glyph: "+glyph);
    }
    /** Exercise the production resolution policy, then compose its final-sized raster 1:1.
     * This models pixel delivery without pretending to test an OEM launcher's compositor.
     */
    static void checkPublishedResolution() throws Exception {
        List<kotlin.Pair<Float,Float>> normal=List.of(new kotlin.Pair<>(320f,220f),
            new kotlin.Pair<>(360f,220f),new kotlin.Pair<>(420f,240f),new kotlin.Pair<>(420f,220f));
        for(int count:new int[]{1,2,4}) {
            List<WidgetRenderSize> sizes=WidgetRenderSize.forHosts(normal.subList(0,count),3f,1080,2340);
            long pixels=0;
            require(sizes.size()==count,"Responsive variants were lost by the bitmap allocation policy");
            for(int i=0;i<sizes.size();i++) {
                WidgetRenderSize size=sizes.get(i);kotlin.Pair<Float,Float> host=normal.get(i);
                require(size.getPixelWidth()==Math.round(host.getFirst()*3f),
                    "Normal density-3 widget is horizontally undersampled: "+size);
                require(size.getPixelHeight()==Math.round(host.getSecond()*3f),
                    "Normal density-3 widget is vertically undersampled: "+size);
                pixels+=(long)size.getPixelWidth()*size.getPixelHeight();
                checkNativeComposition(size,i==0&&count==1);
            }
            require(pixels*4<=Math.floor(1080d*2340*6*0.9),
                "Responsive bitmap bundle exceeds its reserved Android widget memory budget: "+pixels*4);
        }
        List<kotlin.Pair<Float,Float>> extreme=List.of(new kotlin.Pair<>(600f,420f),
            new kotlin.Pair<>(600f,420f),new kotlin.Pair<>(600f,420f),new kotlin.Pair<>(600f,420f));
        List<WidgetRenderSize> limited=WidgetRenderSize.forHosts(extreme,5f,1080,2340);
        long pixels=0;
        for(WidgetRenderSize size:limited) {
            require(size.getPixelWidth()>0&&size.getPixelHeight()>0,"Memory-bounded widget became empty");
            require(size.getDensity()<=5f,"Bitmap policy unnecessarily supersampled an extreme host");
            require(Math.abs(size.getPixelWidth()/(float)size.getPixelHeight()-600f/420f)<0.003f,
                "Memory limit distorts the widget's aspect ratio");
            pixels+=(long)size.getPixelWidth()*size.getPixelHeight();
        }
        require(pixels*4<=Math.floor(1080d*2340*6*0.9),
            "Four extreme responsive variants exceed aggregate memory budget: "+pixels*4);
    }
    static void checkNativeComposition(WidgetRenderSize size,boolean export) throws Exception {
        int width=size.getPixelWidth(),height=size.getPixelHeight();float density=size.getDensity();
        int padding=Math.round(16*density);
        int forecastWidth=Math.round((width-padding*2)*0.57f);
        int forecastHeight=Math.round(Math.min(Math.max(size.getHeight()-32,78),220)*density);
        int left=width-padding-forecastWidth,top=(height-forecastHeight)/2;
        List<ForecastArtwork.Row> rows=rows("partly_cloudy_day",false,Color.WHITE);
        for(ForecastArtwork.Row row:rows)row.getDrop().setTint(Color.WHITE);
        Bitmap published=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        published.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas=new Canvas(published);canvas.translate(left,top);
        List<ForecastArtwork.Cell> cells=ForecastArtwork.draw(canvas,forecastWidth,forecastHeight,density,
            14*density,regular,medium,Color.WHITE,rows,false);
        Bitmap hosted=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        hosted.setDensity(Bitmap.DENSITY_NONE);
        Paint hostPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(hosted).drawBitmap(published,null,new Rect(0,0,width,height),hostPaint);
        int[] originalPixels=new int[width*height],hostPixels=new int[width*height];
        published.getPixels(originalPixels,0,width,0,0,width,height);
        hosted.getPixels(hostPixels,0,width,0,0,width,height);
        require(Arrays.equals(originalPixels,hostPixels),
            "Native-size bitmap delivery unexpectedly resamples the published raster");
        for(ForecastArtwork.Cell cell:cells) {
            RectF region=new RectF(cell.getIconRegion());region.offset(left,top);
            Rect ink=opaqueBounds(hosted,region);PointF visual=visualCenter(hosted,region,Color.WHITE);
            require(!ink.isEmpty()&&ink.height()>=Math.floor(10*density),
                "Forecast icon loses its native resolution in the published widget");
            require(Math.abs(visual.x-left-cell.getIconCenterX())<=1.1f,
                "Published icon's visible horizontal center moved");
            require(Math.abs(visual.y-top-cell.getCenterY())<=1.1f,
                "Published icon's visible vertical center moved");
        }
        if(export)try(OutputStream stream=new FileOutputStream(SAMPLES+"published-native-density-3.png")) {
            hosted.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
        hosted.recycle();published.recycle();
    }
    static List<ForecastArtwork.Row> rows(String condition, boolean daily,int color) throws Exception {
        String[] times={"12:00 PM","03:00 PM","06:00 PM","09:00 PM"};
        String[] days={"Today","Tue","Wed","Thu"};
        List<ForecastArtwork.Row> rows=new ArrayList<>();
        for(int i=0;i<4;i++) rows.add(new ForecastArtwork.Row(daily ? days[i] : times[i],
            daily ? "20° 13°" : (17-i)+"°", daily ? null : "0%",
            weatherVector(condition,color),
            daily ? weatherVector("clear_night",color) : null,
            new VectorFile("app/src/main/res/drawable/glyph_drop.xml")));
        return rows;
    }
    /** Scan only glyph pixels, with empty weather/drop drawables so silhouette edges cannot mimic letters. */
    static void checkClockColumns(float[] size, int color, boolean rtl, boolean twelveHour, boolean padded) {
        checkClockColumns(size,color,rtl,twelveHour,padded,twelveHour);
    }
    static void checkClockColumns(float[] size, int color, boolean rtl, boolean twelveHour, boolean padded,
                                  boolean suffixVisible) {
        int width=(int)size[0], height=(int)size[1];
        String[] times=twelveHour ? padded ? new String[]{"12:00 AM","03:00 PM","06:00 AM","09:00 PM"} :
            new String[]{"12:00 PM","3:00 PM","6:00 AM","9:00 AM"} :
            new String[]{"00:00","03:00","12:00","21:00"};
        if(twelveHour&&!suffixVisible) for(int i=0;i<times.length;i++) times[i]=times[i].split(" ")[0];
        List<ForecastArtwork.Row> rows=new ArrayList<>();
        for(String time:times) rows.add(new ForecastArtwork.Row(time,"17°","0%",new EmptyDrawable(),null,null));
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        List<ForecastArtwork.Cell> cells=ForecastArtwork.draw(new Canvas(bitmap),width,height,size[2],size[3],
            regular,medium,color,rows,rtl);
        int minClockEnd=Integer.MAX_VALUE,maxClockEnd=Integer.MIN_VALUE;
        int minClockStart=Integer.MAX_VALUE,maxClockStart=Integer.MIN_VALUE;
        int minColonStart=Integer.MAX_VALUE,maxColonStart=Integer.MIN_VALUE;
        int minSuffixStart=Integer.MAX_VALUE,maxSuffixStart=Integer.MIN_VALUE;
        float minClockBottom=Float.MAX_VALUE,maxClockBottom=-Float.MAX_VALUE;
        float minSuffixBottom=Float.MAX_VALUE,maxSuffixBottom=-Float.MAX_VALUE;
        List<String> clockDiagnostics=new ArrayList<>();
        for(int i=0;i<4;i++) {
            RectF rowRegion=new RectF(0,i*height/4f,width,(i+1)*height/4f-size[2]);
            List<Rect> runs=horizontalInkRuns(bitmap,rowRegion);
            String clock=times[i].split(" ")[0];
            // Small hinted glyphs can touch in x projection (notably the two zeroes),
            // so horizontal run counts are not character counts. Isolate the label
            // across its wide empty gap from the rain group, then its spaced suffix.
            int start=0,end=runs.size();
            if(rtl) {
                for(int j=runs.size()-1;j>0;j--) if(runs.get(j).left-runs.get(j-1).right>=15*size[2]) {start=j;break;}
            } else {
                for(int j=1;j<runs.size();j++) if(runs.get(j).left-runs.get(j-1).right>=15*size[2]) {end=j;break;}
            }
            int suffixStart=end;
            if(suffixVisible) for(int j=end-1;j>start;j--)
                if(runs.get(j).left-runs.get(j-1).right>=4*size[2]-1) {suffixStart=j;break;}
            require(start<suffixStart&&(!suffixVisible||suffixStart<end),
                "Clock/suffix groups missing: "+times[i]+" at "+width+", rtl="+rtl+", runs="+runs);
            Rect clockInk=new Rect(runs.get(start));
            for(int j=start+1;j<suffixStart;j++) clockInk.union(runs.get(j));
            minClockEnd=Math.min(minClockEnd,clockInk.right);maxClockEnd=Math.max(maxClockEnd,clockInk.right);
            if(padded) {
                // 03/06/09 share the same visible first digit. Leading 1 has narrower natural ink,
                // so compare its colon column instead of stretching the digit to match 0.
                if(i>0) {minClockStart=Math.min(minClockStart,clockInk.left);maxClockStart=Math.max(maxClockStart,clockInk.left);}
                Rect colon=colonInk(bitmap,clockInk);
                require(!colon.isEmpty(),"Padded clock colon is missing: "+times[i]+", clock="+clockInk+", runs="+runs);
                int colonStart=colon.left;
                minColonStart=Math.min(minColonStart,colonStart);maxColonStart=Math.max(maxColonStart,colonStart);
            }
            // Compare against actual content centers: the last row has no divider, and may be a pixel taller.
            float clockBottom=clockInk.bottom-cells.get(i).getCenterY();
            clockDiagnostics.add(times[i]+": ink="+clockInk+", centerY="+cells.get(i).getCenterY()+", bottom="+clockBottom+
                ", labelRuns="+runs.subList(start,end)+", suffixIndex="+(suffixStart-start));
            minClockBottom=Math.min(minClockBottom,clockBottom);maxClockBottom=Math.max(maxClockBottom,clockBottom);
            if(twelveHour&&!suffixVisible) {
                require(clock.length()==5&&clock.indexOf(':')==2,
                    "Hidden-period clock lost its leading zero: "+times[i]);
                require(Math.abs(clockInk.exactCenterY()-cells.get(i).getCenterY())<=1.1f,
                    "Hidden-period numeric clock is not vertically centered: "+times[i]+" at "+width+", rtl="+rtl);
            }
            if(suffixVisible) {
                Rect suffixInk=new Rect(runs.get(suffixStart));
                for(int j=suffixStart+1;j<end;j++) suffixInk.union(runs.get(j));
                minSuffixStart=Math.min(minSuffixStart,suffixInk.left);maxSuffixStart=Math.max(maxSuffixStart,suffixInk.left);
                float suffixBottom=suffixInk.bottom-cells.get(i).getCenterY();
                minSuffixBottom=Math.min(minSuffixBottom,suffixBottom);maxSuffixBottom=Math.max(maxSuffixBottom,suffixBottom);
                if(Math.abs(clockInk.bottom-suffixInk.bottom)>2) try(OutputStream stream=
                        new FileOutputStream(SAMPLES+"clock-diagnostic.png")) {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);
                } catch(IOException error) {throw new UncheckedIOException(error);}
                require(suffixInk.left>clockInk.right,"Clock and suffix overlap: "+times[i]+" at "+width+", rtl="+rtl);
                require(Math.abs(clockInk.bottom-suffixInk.bottom)<=2,
                    "Clock/suffix visible baseline mismatch: "+times[i]+" at "+width+", rtl="+rtl+
                    ", color="+color+", clock="+clockInk+", suffix="+suffixInk+", runs="+runs);
            }
        }
        String context=" at "+width+", color="+color+", rtl="+rtl+", twelveHour="+twelveHour+
            ", padded="+padded+", suffixVisible="+suffixVisible;
        require(maxClockEnd-minClockEnd<=1,"Visible numeric clock ends do not share one column"+context+
            ": "+minClockEnd+".."+maxClockEnd+", "+clockDiagnostics);
        if(padded) {
            require(maxClockStart-minClockStart<=1,"Visible 03/06/09 numeric clock starts do not share one column"+context+
                ": "+minClockStart+".."+maxClockStart);
            require(maxColonStart-minColonStart<=1,"Visible padded clock colons do not share one column"+context+
                ": "+minColonStart+".."+maxColonStart);
        }
        require(maxClockBottom-minClockBottom<=1,"Clock rows have inconsistent visible baselines"+context+
            ": "+clockDiagnostics);
        if(suffixVisible) {
            require(maxSuffixStart-minSuffixStart<=1,"AM/PM visible starts do not share one column"+context+
                ": "+minSuffixStart+".."+maxSuffixStart);
            require(maxSuffixBottom-minSuffixBottom<=1,"AM/PM rows have inconsistent visible baselines"+context);
        }
        bitmap.recycle();
    }
    /** The shorter time column moves the rain/icon columns; verify their actual pixels after that change. */
    static void checkHiddenPeriodArtwork(float[] size,int color,boolean rtl) throws Exception {
        checkHiddenPeriodArtwork(size,color,rtl,new String[]{"0%","0%","0%","0%"});
        checkHiddenPeriodArtwork(size,color,rtl,new String[]{"0%","9%","45%","100%"});
    }
    static void checkHiddenPeriodArtwork(float[] size,int color,boolean rtl,String[] chances) throws Exception {
        int width=(int)size[0],height=(int)size[1];float density=size[2],textSize=size[3];
        String[] times={"12:00","03:00","06:00","09:00"};
        String[] conditions={"clear_day","partly_cloudy_day","overcast","clear_night"};
        List<ForecastArtwork.Row> rows=new ArrayList<>();
        for(int i=0;i<4;i++) {
            VectorFile drop=new VectorFile("app/src/main/res/drawable/glyph_drop.xml");drop.setTint(color);
            rows.add(new ForecastArtwork.Row(times[i],"17°",chances[i],weatherVector(conditions[i],color),null,drop));
        }
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        List<ForecastArtwork.Cell> cells=ForecastArtwork.draw(new Canvas(bitmap),width,height,density,textSize,
            regular,medium,color,rows,rtl);
        Paint valuePaint=new Paint();valuePaint.setTypeface(medium);valuePaint.setTextSize(textSize*15/14);
        valuePaint.setFontVariationSettings("'wght' 500");valuePaint.setFontFeatureSettings("tnum");
        float valueWidth=valuePaint.measureText("17°");
        // Normalize the scan direction, including RTL. These checks use visible glyph
        // and silhouette edges rather than reproducing the painter's gap formula.
        Bitmap logical=bitmap;
        if(rtl) {
            logical=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas mirrored=new Canvas(logical);mirrored.translate(width,0);mirrored.scale(-1,1);mirrored.drawBitmap(bitmap,0,0,null);
        }
        for(int i=0;i<cells.size();i++) {
            ForecastArtwork.Cell cell=cells.get(i);
            PointF visual=visualCenter(bitmap,cell.getIconRegion(),color);
            Rect ink=opaqueBounds(bitmap,cell.getIconRegion());
            String context="hidden-period "+conditions[i]+" at "+width+", rtl="+rtl+", color="+color+", chance="+chances[i];
            require(!ink.isEmpty()&&ink.height()>=5,"Missing/collapsed "+context);
            require(Math.abs(visual.x-cell.getIconCenterX())<=1.1f,"Horizontal center displaced: "+context);
            require(Math.abs(visual.y-cell.getCenterY())<=1.1f,"Vertical center displaced: "+context);
            RectF region=cell.getIconRegion();
            RectF logicalIconRegion=rtl?new RectF(width-region.right,region.top,width-region.left,region.bottom):region;
            Rect logicalIconInk=opaqueBounds(logical,logicalIconRegion);
            RectF valueRegion=new RectF(width-valueWidth,region.top,width,region.bottom);
            Rect valueInk=opaqueBounds(logical,valueRegion);
            require(!valueInk.isEmpty(),"Missing temperature: "+context);
            require(Math.abs(valueInk.exactCenterY()-visual.y)<=1.1f,"Number/icon heights disagree: "+context);
            List<Rect> leadingRuns=horizontalInkRuns(logical,new RectF(0,region.top,logicalIconRegion.left,region.bottom));
            require(leadingRuns.size()>=3,"Clock/rain groups missing: "+context+", runs="+leadingRuns);
            // Character projections can merge at compact sizes. Follow the whole
            // numeric word until a gap exceeds its internal spacing, using the
            // actual glyph height; then isolate the separately spaced droplet.
            Rect timeInk=new Rect(leadingRuns.get(0));int dropStart=1;
            for(;dropStart<leadingRuns.size();dropStart++) {
                float gap=leadingRuns.get(dropStart).left-leadingRuns.get(dropStart-1).right;
                if(gap>=Math.max(2*density,timeInk.height()/2f))break;
                timeInk.union(leadingRuns.get(dropStart));
            }
            require(dropStart<leadingRuns.size()-1,"Clock/drop separation missing: "+context+", runs="+leadingRuns);
            Rect dropInk=new Rect(leadingRuns.get(dropStart));int chanceStart=dropStart+1;
            for(;chanceStart<leadingRuns.size();chanceStart++) {
                if(leadingRuns.get(chanceStart).left-leadingRuns.get(chanceStart-1).right>=2*density)break;
                dropInk.union(leadingRuns.get(chanceStart));
            }
            require(chanceStart<leadingRuns.size(),"Drop/chance separation missing: "+context+", runs="+leadingRuns);
            Rect chanceInk=opaqueBounds(logical,new RectF(dropInk.right,region.top,logicalIconRegion.left,region.bottom));
            require(!chanceInk.isEmpty(),"Missing rain chance: "+context);
            RectF dropRegion=new RectF(dropInk.left-1,region.top,dropInk.right+1,region.bottom);
            checkRainFigurePixels(logical,cell,chanceInk,valueInk,dropRegion,color,density,textSize,
                chances[i],"17°",context);
            float logicalCenter=rtl?width-visual.x:visual.x;
            float gapCenter=(chanceInk.right+valueInk.left)/2f;
            require(Math.abs(logicalCenter-gapCenter)<=1.1f,"Icon is not midway between rain/temperature: "+context);
            float timeGap=dropInk.left-timeInk.right;
            float rainGap=logicalIconInk.left-chanceInk.right;
            float valueGap=valueInk.left-logicalIconInk.right;
            float minimum=Math.min(timeGap,Math.min(rainGap,valueGap));
            float maximum=Math.max(timeGap,Math.max(rainGap,valueGap));
            require(timeGap>=4*density,"Time still crowds the droplet: "+context+", gap="+timeGap);
            require(minimum>=2*density,"Forecast groups overlap or crowd: "+context+
                ", time/rain/value gaps="+timeGap+"/"+rainGap+"/"+valueGap);
            require(maximum<=minimum*1.5f+5*density,"Spare width is still concentrated around the weather icon: "+context+
                ", time/rain/value gaps="+timeGap+"/"+rainGap+"/"+valueGap+", runs="+leadingRuns+
                ", time="+timeInk+", drop="+dropInk+", chance="+chanceInk+", icon="+logicalIconInk);
            require(Math.abs(timeInk.exactCenterY()-cell.getCenterY())<=1.1f,"Spaced time lost vertical centering: "+context);
        }
        if(rtl)logical.recycle();
        bitmap.recycle();
    }
    static void checkRainFigurePixels(Bitmap bitmap,ForecastArtwork.Cell cell,Rect chanceInk,Rect valueInk,
                                      RectF dropRegion,int color,float density,float textSize,
                                      String chance,String temperature,String context) {
        // Check visible results rather than text advances: larger figures retain
        // their row center, while only the droplet moves down by half a dp.
        int oldChanceHeight=referenceTextHeight(chance,textSize*12/14,regular,400);
        int oldTemperatureHeight=referenceTextHeight(temperature,textSize,medium,500);
        require(chanceInk.height()>oldChanceHeight,"Rain percentage did not visibly grow: "+context+
            ", old/new heights="+oldChanceHeight+"/"+chanceInk.height());
        require(valueInk.height()>oldTemperatureHeight,"Forecast temperature did not visibly grow: "+context+
            ", old/new heights="+oldTemperatureHeight+"/"+valueInk.height());
        require(Math.abs(chanceInk.exactCenterY()-cell.getCenterY())<=1.1f,
            "Larger rain percentage lost its row center: "+context);
        PointF dropCenter=visualCenter(bitmap,dropRegion,color);
        require(Math.abs(dropCenter.y-cell.getCenterY()-0.5f*density)<=0.6f,
            "Droplet did not retain its slight downward adjustment: "+context+
            ", offset="+(dropCenter.y-cell.getCenterY()));
    }
    static int referenceTextHeight(String text,float size,Typeface font,int weight) {
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(font);paint.setTextSize(size);
        paint.setFontVariationSettings("'wght' "+weight);paint.setFontFeatureSettings("tnum");
        int padding=(int)Math.ceil(size/2)+2;Paint.FontMetrics metrics=paint.getFontMetrics();
        Bitmap reference=Bitmap.createBitmap((int)Math.ceil(paint.measureText(text))+padding*2,
            (int)Math.ceil(metrics.bottom-metrics.top)+padding*2,Bitmap.Config.ARGB_8888);
        new Canvas(reference).drawText(text,padding,padding-metrics.top,paint);
        int height=opaqueBounds(reference,new RectF(0,0,reference.getWidth(),reference.getHeight())).height();
        reference.recycle();return height;
    }
    static List<Rect> horizontalInkRuns(Bitmap bitmap,RectF region) {
        List<Rect> runs=new ArrayList<>();Rect current=null;
        int top=Math.max(0,(int)Math.ceil(region.top)),bottom=Math.min(bitmap.getHeight(),(int)Math.floor(region.bottom));
        for(int x=Math.max(0,(int)Math.ceil(region.left));x<Math.min(bitmap.getWidth(),(int)Math.floor(region.right));x++) {
            int inkTop=bottom,inkBottom=-1;
            for(int y=top;y<bottom;y++) if(Color.alpha(bitmap.getPixel(x,y))>=128) {inkTop=Math.min(inkTop,y);inkBottom=Math.max(inkBottom,y);}
            if(inkBottom>=inkTop) {
                if(current==null) current=new Rect(x,inkTop,x+1,inkBottom+1);
                else current.union(x,inkTop,x+1,inkBottom+1);
            } else if(current!=null) {runs.add(current);current=null;}
        }
        if(current!=null) runs.add(current);
        return runs;
    }
    /** Find the colon's two interior dots; their x projection remains reliable even
     * when neighboring zero outlines touch and merge into one horizontal run. */
    static Rect colonInk(Bitmap bitmap,Rect clock) {
        int width=clock.width(),height=clock.height();boolean[] visited=new boolean[width*height];
        List<Rect> dots=new ArrayList<>();
        for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
            int start=y*width+x;if(visited[start]||Color.alpha(bitmap.getPixel(clock.left+x,clock.top+y))<128)continue;
            ArrayDeque<Integer> queue=new ArrayDeque<>();queue.add(start);visited[start]=true;Rect component=new Rect();
            while(!queue.isEmpty()) {
                int position=queue.removeFirst(),px=position%width,py=position/width;
                if(component.isEmpty())component.set(clock.left+px,clock.top+py,clock.left+px+1,clock.top+py+1);
                else component.union(clock.left+px,clock.top+py,clock.left+px+1,clock.top+py+1);
                for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++) {
                    int nx=px+dx,ny=py+dy;if(nx<0||nx>=width||ny<0||ny>=height)continue;
                    int index=ny*width+nx;
                    if(!visited[index]&&Color.alpha(bitmap.getPixel(clock.left+nx,clock.top+ny))>=128) {
                        visited[index]=true;queue.add(index);
                    }
                }
            }
            if(component.top>clock.top&&component.bottom<clock.bottom&&component.width()<=height*.35f+1&&
                component.height()<=height*.35f+1)dots.add(component);
        }
        Rect colon=new Rect();float nearest=Float.MAX_VALUE;
        for(Rect a:dots)for(Rect b:dots)if(a.bottom<b.top&&Math.abs(a.exactCenterX()-b.exactCenterX())<=1.1f) {
            float distance=Math.abs((a.exactCenterX()+b.exactCenterX())/2-clock.exactCenterX());
            if(distance<nearest) {colon.set(a);colon.union(b);nearest=distance;}
        }
        return colon;
    }
    static double weightedArea(Bitmap bitmap,RectF region,int color) {
        double area=0;
        for(int y=Math.max(0,(int)Math.ceil(region.top));y<Math.min(bitmap.getHeight(),(int)Math.floor(region.bottom));y++)
            for(int x=Math.max(0,(int)Math.ceil(region.left));x<Math.min(bitmap.getWidth(),(int)Math.floor(region.right));x++) {
                int pixel=bitmap.getPixel(x,y),alpha=Color.alpha(pixel);if(alpha<8)continue;
                double brightness=(.2126*Color.red(pixel)+.7152*Color.green(pixel)+.0722*Color.blue(pixel))/255;
                if(color==Color.BLACK)brightness=1-brightness;
                area+=alpha/255.0*(.25+.75*brightness);
            }
        return area;
    }
    static void checkWeatherSizes() throws Exception {
        for(int color:new int[]{Color.WHITE,Color.BLACK}) for(boolean rtl:new boolean[]{false,true}) {
            double[] areas=new double[2];String[] conditions={"partly_cloudy_day","overcast"};
            for(int i=0;i<2;i++) {
                Bitmap bitmap=Bitmap.createBitmap(561,564,Bitmap.Config.ARGB_8888);
                List<ForecastArtwork.Row> rows=rows(conditions[i],false,color);for(ForecastArtwork.Row row:rows)row.getDrop().setTint(color);
                List<ForecastArtwork.Cell> cells=ForecastArtwork.draw(new Canvas(bitmap),561,564,3,42,regular,medium,color,rows,rtl);
                areas[i]=weightedArea(bitmap,cells.get(0).getIconRegion(),color);bitmap.recycle();
            }
            double ratio=Math.max(areas[0],areas[1])/Math.min(areas[0],areas[1]);
            require(Double.isFinite(ratio)&&ratio<=1.20,"Partly-cloudy/overcast apparent area still differs too much: "+
                ratio+", areas="+Arrays.toString(areas)+", rtl="+rtl+", color="+color);
        }
    }
    static void checkMoonPalette() throws Exception {
        for(int color:new int[]{Color.WHITE,Color.BLACK}) {
            Bitmap bitmap=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
            VectorFile moon=weatherVector("clear_night",color);moon.setBounds(0,0,192,192);moon.draw(new Canvas(bitmap));
            double contrast=0;int corePixels=0;
            for(int y=0;y<192;y++)for(int x=0;x<192;x++) {
                int pixel=bitmap.getPixel(x,y);if(Color.alpha(pixel)<240)continue;
                double brightness=.2126*Color.red(pixel)+.7152*Color.green(pixel)+.0722*Color.blue(pixel);
                contrast+=color==Color.BLACK?255-brightness:brightness;corePixels++;
            }
            require(corePixels>100,"Moon has no opaque body");
            require(contrast/corePixels>=230,"Moon body does not match its selected icon palette: "+contrast/corePixels+", color="+color);
            bitmap.recycle();
        }
    }
    /** Test opaque bodies, not antialiased fringes, against the panel on which users see them. */
    static void checkIconContrast() throws Exception {
        for(String condition:CONDITIONS) for(int color:new int[]{Color.WHITE,Color.BLACK}) {
            Bitmap current=Bitmap.createBitmap(192,192,Bitmap.Config.ARGB_8888);
            VectorFile vector=weatherVector(condition,color);vector.setBounds(0,0,192,192);vector.draw(new Canvas(current));
            checkVisibleContrast(current,new RectF(0,0,192,192),color,64,250,
                "current "+condition);current.recycle();
            for(boolean daily:new boolean[]{false,true}) for(boolean rtl:new boolean[]{false,true}) {
                List<ForecastArtwork.Row> rows=rows(condition,daily,color);
                for(ForecastArtwork.Row row:rows)row.getDrop().setTint(color);
                Bitmap forecast=Bitmap.createBitmap(561,564,Bitmap.Config.ARGB_8888);
                List<ForecastArtwork.Cell> cells=ForecastArtwork.draw(new Canvas(forecast),561,564,3,42,
                    regular,medium,color,rows,rtl);
                checkVisibleContrast(forecast,cells.get(0).getIconRegion(),color,12,80,
                    (daily?"daily primary ":"three-hour ")+condition+", rtl="+rtl);
                forecast.recycle();
                if(daily) {
                    List<ForecastArtwork.Row> nightRows=new ArrayList<>();
                    for(ForecastArtwork.Row row:rows)nightRows.add(new ForecastArtwork.Row(row.getLabel(),row.getValue(),null,
                        weatherVector("partly_cloudy_day",color),weatherVector(condition,color),row.getDrop()));
                    Bitmap night=Bitmap.createBitmap(561,564,Bitmap.Config.ARGB_8888);
                    List<ForecastArtwork.Cell> nightCells=ForecastArtwork.draw(new Canvas(night),561,564,3,42,
                        regular,medium,color,nightRows,rtl);
                    RectF primary=nightCells.get(0).getIconRegion();
                    RectF nightRegion=new RectF(primary);nightRegion.offset(rtl?-primary.width():primary.width(),0);
                    checkVisibleContrast(night,nightRegion,color,12,80,"daily night "+condition+", rtl="+rtl);
                    night.recycle();
                }
            }
        }
    }
    static void checkVisibleContrast(Bitmap artwork,RectF region,int color,int minimumCore,double minimumMass,String context) {
        int core=0,strongCore=0;double coreContrast=0,visibleMass=0;
        double background=color==Color.BLACK?242:37;
        for(int y=Math.max(0,(int)Math.ceil(region.top));y<Math.min(artwork.getHeight(),(int)Math.floor(region.bottom));y++)
            for(int x=Math.max(0,(int)Math.ceil(region.left));x<Math.min(artwork.getWidth(),(int)Math.floor(region.right));x++) {
                int pixel=artwork.getPixel(x,y),alpha=Color.alpha(pixel);if(alpha<8)continue;
                double brightness=.2126*Color.red(pixel)+.7152*Color.green(pixel)+.0722*Color.blue(pixel);
                double contrast=color==Color.BLACK?background-brightness:brightness-background;
                visibleMass+=alpha/255.0*Math.max(0,contrast)/255;
                if(alpha>=240) {core++;coreContrast+=contrast;if(contrast>=64)strongCore++;}
            }
        require(core>=minimumCore,"Opaque icon body missing: "+context+", color="+color+", core="+core);
        require(coreContrast/core>=96,"Icon body blends into its panel: "+context+", color="+color+
            ", average contrast="+coreContrast/core);
        require(strongCore/(double)core>=0.90,"Most opaque icon pixels lack meaningful panel contrast: "+context+
            ", color="+color+", visible fraction="+strongCore/(double)core);
        require(visibleMass>=minimumMass,"Only a tiny icon fringe is visible: "+context+", color="+color+
            ", contrast-weighted mass="+visibleMass);
    }
    static Rect opaqueBounds(Bitmap bitmap, RectF region) {
        int left=bitmap.getWidth(),top=bitmap.getHeight(),right=-1,bottom=-1;
        for(int y=Math.max(0,(int)Math.ceil(region.top));y<Math.min(bitmap.getHeight(),(int)Math.floor(region.bottom));y++)
            for(int x=Math.max(0,(int)Math.ceil(region.left));x<Math.min(bitmap.getWidth(),(int)Math.floor(region.right));x++)
                if(Color.alpha(bitmap.getPixel(x,y)) >= 128) {left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
        return right < left ? new Rect() : new Rect(left,top,right+1,bottom+1);
    }
    static PointF visualCenter(Bitmap bitmap,RectF region,int color) {
        double mass=0,mx=0,my=0;
        for(int y=Math.max(0,(int)Math.ceil(region.top));y<Math.min(bitmap.getHeight(),(int)Math.floor(region.bottom));y++)
            for(int x=Math.max(0,(int)Math.ceil(region.left));x<Math.min(bitmap.getWidth(),(int)Math.floor(region.right));x++) {
                int pixel=bitmap.getPixel(x,y);int alpha=Color.alpha(pixel);if(alpha<8)continue;
                double brightness=(.2126*Color.red(pixel)+.7152*Color.green(pixel)+.0722*Color.blue(pixel))/255;
                if(color==Color.BLACK)brightness=1-brightness;
                double weight=alpha*(.25+.75*brightness);mass+=weight;mx+=(x+.5)*weight;my+=(y+.5)*weight;
            }
        return new PointF((float)(mx/mass),(float)(my/mass));
    }
    static void exportSample(boolean daily) throws Exception {
        int w=561,h=564;
        Bitmap output=Bitmap.createBitmap(w*2+36,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output); canvas.drawColor(Color.rgb(110,110,110));
        for(int i=0;i<2;i++) {
            int color=i==0?Color.WHITE:Color.BLACK;
            List<ForecastArtwork.Row> rows=rows("partly_cloudy_day",daily,color);
            if(!daily) rows.set(3,new ForecastArtwork.Row("09:00 PM","13°","0%",
                weatherVector("overcast",color),null,
                new VectorFile("app/src/main/res/drawable/glyph_drop.xml")));
            for(ForecastArtwork.Row row:rows)row.getDrop().setTint(color);
            canvas.save();canvas.translate(i*(w+36),0);paintPanel(canvas,w,h,color);
            ForecastArtwork.draw(canvas,w,h,3,42,regular,medium,color,rows,false);
            canvas.restore();
        }
        try(OutputStream stream=new FileOutputStream(SAMPLES+"forecast-"+(daily?"daily":"three-hour")+".png")) {
            output.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
    }
    static void exportMixedSample(boolean daily,boolean twelveHour) throws Exception {
        exportMixedSample(daily,twelveHour,twelveHour);
    }
    static void exportMixedSample(boolean daily,boolean twelveHour,boolean suffixVisible) throws Exception {
        int w=561,h=564,gap=24;
        Bitmap output=Bitmap.createBitmap(w*3+gap*2,h*2+gap,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);canvas.drawColor(Color.rgb(110,110,110));
        String[] times=twelveHour?new String[]{"12:00 AM","03:00 PM","06:00 AM","09:00 PM"}:
            new String[]{"12:00","15:00","18:00","21:00"};
        if(twelveHour&&!suffixVisible) for(int i=0;i<times.length;i++) times[i]=times[i].split(" ")[0];
        String[] days={"Today","Tue","Wed","Thu"};
        String[] night={"clear_night","clear_night","partly_cloudy_night","partly_cloudy_night","overcast",
            "overcast_rain","overcast_snow","thunderstorms","mist","not_available"};
        for(int colorIndex=0;colorIndex<2;colorIndex++) for(int panel=0;panel<3;panel++) {
            int color=colorIndex==0?Color.WHITE:Color.BLACK;
            List<ForecastArtwork.Row> rows=new ArrayList<>();
            for(int row=0;row<4;row++) {
                int condition=(panel*4+row)%CONDITIONS.length;
                VectorFile drop=new VectorFile("app/src/main/res/drawable/glyph_drop.xml");drop.setTint(color);
                rows.add(new ForecastArtwork.Row(daily?days[row]:times[row],daily?"20° 11°":(17-row)+"°",daily?null:"0%",
                    weatherVector(CONDITIONS[condition],color),
                    daily?weatherVector(night[condition],color):null,drop));
            }
            canvas.save();canvas.translate(panel*(w+gap),colorIndex*(h+gap));paintPanel(canvas,w,h,color);
            ForecastArtwork.draw(canvas,w,h,3,42,regular,medium,color,rows,false);canvas.restore();
        }
        String mode=daily?"daily":twelveHour?suffixVisible?"12-hour":"12-hour-no-period":"24-hour";
        try(OutputStream stream=new FileOutputStream(SAMPLES+"forecast-mixed-"+mode+".png")) {
            output.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
        output.recycle();
    }
    static void paintPanel(Canvas canvas,int width,int height,int color) {
        Paint panel=new Paint();panel.setColor(color==Color.BLACK?Color.rgb(242,242,242):Color.rgb(37,37,37));
        canvas.drawRect(0,0,width,height,panel);
    }
    static void exportCurrentIcons() throws Exception {
        int width=256,height=288,gap=16;
        Bitmap output=Bitmap.createBitmap(width*5+gap*4,height*4+gap*3,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(output);canvas.drawColor(Color.rgb(110,110,110));
        for(int colorIndex=0;colorIndex<2;colorIndex++) for(int index=0;index<CONDITIONS.length;index++) {
            int color=colorIndex==0?Color.WHITE:Color.BLACK;
            int column=index%5,row=colorIndex*2+index/5;
            canvas.save();canvas.translate(column*(width+gap),row*(height+gap));paintPanel(canvas,width,height,color);
            VectorFile vector=weatherVector(CONDITIONS[index],color);vector.setBounds(32,16,224,208);vector.draw(canvas);
            Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setTypeface(regular);label.setTextSize(18);label.setColor(color);
            canvas.drawText(CONDITIONS[index],16,256,label);canvas.restore();
        }
        try(OutputStream stream=new FileOutputStream(SAMPLES+"current-icon-contrast.png")) {
            output.compress(Bitmap.CompressFormat.PNG,100,stream);
        }
        output.recycle();
    }
    static final class EmptyDrawable extends Drawable {
        public void draw(Canvas canvas) { }
        public void setAlpha(int alpha) { }
        public void setColorFilter(ColorFilter filter) { }
        public int getOpacity() {return PixelFormat.TRANSPARENT;}
    }
    /** Decodes the project's exact vector paths/gradients into native Android Canvas operations. */
    static final class VectorFile extends Drawable {
        final Element root; final float viewportWidth,viewportHeight; Integer tint;
        VectorFile(String path) throws Exception {
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            root=factory.newDocumentBuilder().parse(new File(path)).getDocumentElement();
            viewportWidth=number(root,"viewportWidth",24); viewportHeight=number(root,"viewportHeight",24);
        }
        public void draw(Canvas canvas) {
            canvas.save();canvas.translate(getBounds().left,getBounds().top);
            canvas.scale(getBounds().width()/viewportWidth,getBounds().height()/viewportHeight);
            drawChildren(canvas,root);canvas.restore();
        }
        void drawChildren(Canvas canvas,Element element) {
            for(Node node=element.getFirstChild();node!=null;node=node.getNextSibling()) if(node instanceof Element) {
                Element e=(Element)node;
                if(e.getTagName().equals("group")) {
                    canvas.save();canvas.translate(number(e,"translateX",0),number(e,"translateY",0));
                    canvas.scale(number(e,"scaleX",1),number(e,"scaleY",1));
                    drawChildren(canvas,e);canvas.restore();
                } else if(e.getTagName().equals("path")) {
                    Path path=PathParser.createPathFromPathData(e.getAttributeNS(ANDROID,"pathData"));
                    Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
                    String fill=e.getAttributeNS(ANDROID,"fillColor");
                    NodeList gradients=e.getElementsByTagName("gradient");
                    if(gradients.getLength()>0) {
                        Element gradient=(Element)gradients.item(0);NodeList stops=gradient.getElementsByTagName("item");
                        int[] colors=new int[stops.getLength()];float[] positions=new float[stops.getLength()];
                        for(int s=0;s<stops.getLength();s++) {Element stop=(Element)stops.item(s);colors[s]=Color.parseColor(stop.getAttributeNS(ANDROID,"color"));positions[s]=number(stop,"offset",0);}
                        paint.setShader(new LinearGradient(number(gradient,"startX",0),number(gradient,"startY",0),
                            number(gradient,"endX",1),number(gradient,"endY",1),colors,positions,Shader.TileMode.CLAMP));
                    } else {int fillColor=fill.isEmpty()?Color.BLACK:Color.parseColor(fill);
                        paint.setColor(tint!=null?((fillColor&0xFF000000)|(tint&0xFFFFFF)):fillColor);}
                    paint.setAlpha(Math.round(Color.alpha(paint.getColor())*number(e,"fillAlpha",1)));canvas.drawPath(path,paint);
                    String stroke=e.getAttributeNS(ANDROID,"strokeColor");
                    if(!stroke.isEmpty()) {paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setColor(tint!=null?tint:Color.parseColor(stroke));
                        paint.setStrokeWidth(number(e,"strokeWidth",1));paint.setAlpha(Math.round(Color.alpha(paint.getColor())*number(e,"strokeAlpha",1)));
                        if(e.getAttributeNS(ANDROID,"strokeLineCap").equals("round"))paint.setStrokeCap(Paint.Cap.ROUND);
                        canvas.drawPath(path,paint);}
                }
            }
        }
        static float number(Element element,String name,float fallback) {String value=element.getAttributeNS(ANDROID,name);return value.isEmpty()?fallback:Float.parseFloat(value);}
        public void setAlpha(int alpha) { }
        public void setTint(int color) {tint=color;}
        public void setColorFilter(ColorFilter filter) { }
        public int getOpacity() {return PixelFormat.TRANSLUCENT;}
    }
}
