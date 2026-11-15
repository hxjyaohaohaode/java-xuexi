package io.typeforge.artifacts;

import io.typeforge.shared.DomainException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

/** Bounded file structure validation, not antivirus certification or a guarantee about every media decoder. */
@Component
public class MediaInspector {
    public record Inspection(String mimeType,String extension,Integer width,Integer height,Long durationMillis) {}
    private static final long MAX_PIXELS=16_777_216L;
    private static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    public Inspection inspect(byte[] bytes) {
        if(bytes==null || bytes.length<12) throw invalid();
        if(Arrays.equals(Arrays.copyOf(bytes,8),PNG)) {png(bytes);return image(bytes,"image/png","png");}
        if((bytes[0]&255)==255 && (bytes[1]&255)==216 && (bytes[2]&255)==255) {
            if((bytes[bytes.length-2]&255)!=255 || (bytes[bytes.length-1]&255)!=217) throw invalid();
            return image(bytes,"image/jpeg","jpg");
        }
        if(text(bytes,0,4).equals("RIFF") && text(bytes,8,4).equals("WAVE")) return wav(bytes);
        if(text(bytes,0,3).equals("ID3") || ((bytes[0]&255)==255 && (bytes[1]&224)==224)) return mp3(bytes);
        if(text(bytes,4,4).equals("ftyp")) return mp4(bytes);
        throw new DomainException("ASSET_FORMAT_UNSUPPORTED",400,"Upload a valid PNG, JPEG, PCM WAV, MP3 or MP4 file; SVG and executable formats are not accepted");
    }
    private static Inspection image(byte[] bytes,String mime,String extension) {
        try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext()) throw invalid();var reader=readers.next();
            try {
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);dimensions(width,height);
                var decoded=reader.read(0);if(decoded==null || decoded.getWidth()!=width || decoded.getHeight()!=height) throw invalid();decoded.flush();
                return new Inspection(mime,extension,width,height,null);
            } finally {reader.dispose();}
        } catch(IOException | IllegalArgumentException failure) {throw invalid();}
    }
    private static void png(byte[] bytes) {
        int at=8,chunks=0;boolean data=false,header=false,end=false;
        while(at<bytes.length && ++chunks<=4096) {
            if(bytes.length-at<12) throw invalid();long size=u32(bytes,at,false);if(size>bytes.length-at-12L) throw invalid();int length=(int)size;
            String type=text(bytes,at+4,4);var crc=new CRC32();crc.update(bytes,at+4,length+4);if(crc.getValue()!=u32(bytes,at+8+length,false)) throw invalid();
            if(!header) {if(!type.equals("IHDR") || length!=13) throw invalid();header=true;dimensions((int)u32(bytes,at+8,false),(int)u32(bytes,at+12,false));}
            else if(type.equals("IHDR")) throw invalid();
            if(type.equals("acTL")) throw new DomainException("ASSET_FORMAT_UNSUPPORTED",400,"Animated PNG is not accepted by the still-image validator");
            if(type.equals("IDAT")) data=true;
            at+=length+12;
            if(type.equals("IEND")) {if(length!=0 || at!=bytes.length) throw invalid();end=true;break;}
        }
        if(!header || !data || !end) throw invalid();
    }
    private static Inspection wav(byte[] bytes) {
        if(u32(bytes,4,true)+8!=bytes.length) throw invalid();int at=12,channels=0,bits=0,alignment=0;long sampleRate=0,data=0;boolean format=false;
        while(at<bytes.length) {
            if(bytes.length-at<8) throw invalid();String chunk=text(bytes,at,4);long size=u32(bytes,at+4,true);if(size>bytes.length-at-8L) throw invalid();int payload=at+8;
            if(chunk.equals("fmt ")) {
                if(format || size<16 || u16(bytes,payload)!=1) throw new DomainException("ASSET_FORMAT_UNSUPPORTED",400,"WAV requires uncompressed PCM audio");
                channels=u16(bytes,payload+2);sampleRate=u32(bytes,payload+4,true);long byteRate=u32(bytes,payload+8,true);alignment=u16(bytes,payload+12);bits=u16(bytes,payload+14);
                if(channels<1 || channels>2 || !Set.of(8,16,24,32).contains(bits) || sampleRate<8000 || sampleRate>96000 || alignment!=channels*(bits/8) || byteRate!=sampleRate*alignment) throw invalid();format=true;
            }
            if(chunk.equals("data")) {if(!format || size==0 || size%alignment!=0) throw invalid();data+=size;}
            at=payload+(int)size+(int)(size&1);if(at>bytes.length) throw invalid();
        }
        if(!format || data==0) throw invalid();long duration=data*1000/(sampleRate*alignment);duration(duration);return new Inspection("audio/wav","wav",null,null,duration);
    }
    private static Inspection mp3(byte[] bytes) {
        int at=0,frames=0;double millis=0;
        if(text(bytes,0,3).equals("ID3")) {
            int version=bytes[3]&255;if(version<2 || version>4 || (bytes[5]&15)!=0) throw invalid();int size=0;
            for(int i=6;i<10;i++) {if((bytes[i]&128)!=0) throw invalid();size=(size<<7)|(bytes[i]&127);}
            at=10+size+((version==4 && (bytes[5]&16)!=0)?10:0);if(at>=bytes.length) throw invalid();
        }
        while(at<bytes.length) {
            if(bytes.length-at==128 && text(bytes,at,3).equals("TAG")) {at=bytes.length;break;}
            if(bytes.length-at<4) throw invalid();long header=u32(bytes,at,false);int version=(int)((header>>19)&3),layer=(int)((header>>17)&3),index=(int)((header>>12)&15),sampleIndex=(int)((header>>10)&3),pad=(int)((header>>9)&1);
            if((header&0xffe00000L)!=0xffe00000L || version==1 || layer!=1 || index==0 || index==15 || sampleIndex==3) throw invalid();
            int[] rates=version==3?new int[]{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320}:new int[]{0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};
            int sample=new int[]{44100,48000,32000}[sampleIndex]/(version==3?1:version==2?2:4);int length=(version==3?144000:72000)*rates[index]/sample+pad;
            if(length<24 || length>bytes.length-at) throw invalid();at+=length;frames++;millis+=(version==3?1152d:576d)*1000/sample;
            if(millis>600000) throw invalid();
        }
        if(frames<2 || at!=bytes.length) throw invalid();long duration=Math.round(millis);duration(duration);return new Inspection("audio/mpeg","mp3",null,null,duration);
    }
    private static Inspection mp4(byte[] bytes) {
        var state=new Movie();boxes(bytes,0,bytes.length,0,state,"root");
        if(!state.brand || !state.movie || !state.data || !state.video || state.duration==null || state.width==null || state.height==null) throw invalid();duration(state.duration);dimensions(state.width,state.height);
        return new Inspection("video/mp4","mp4",state.width,state.height,state.duration);
    }
    private static final class Movie {int boxes;boolean brand,movie,data,video;Integer width,height;Long duration;}
    private static void boxes(byte[] bytes,int from,int until,int depth,Movie movie,String parent) {
        if(depth>8) throw invalid();int at=from;
        while(at<until) {
            if(until-at<8 || ++movie.boxes>10000) throw invalid();long size=u32(bytes,at,false);int header=8;String kind=text(bytes,at+4,4);
            if(size==1) {if(until-at<16) throw invalid();size=i64(bytes,at+8);header=16;}else if(size==0) size=until-at;
            if(size<header || size>until-at) throw invalid();int payload=at+header,end=at+(int)size,length=end-payload;
            if(kind.equals("ftyp") && parent.equals("root")) {if(length<8 || length%4!=0) throw invalid();String brand=text(bytes,payload,4);movie.brand=Set.of("isom","iso2","mp41","mp42","avc1","M4V ","dash","iso5","iso6","MSNV").contains(brand);}
            if(kind.equals("mdat") && length>0) movie.data=true;
            if(kind.equals("moov")) movie.movie=true;
            if(kind.equals("mvhd") && parent.equals("moov")) {
                if(length<20) throw invalid();int version=bytes[payload]&255;long scale,ticks;
                if(version==0) {scale=u32(bytes,payload+12,false);ticks=u32(bytes,payload+16,false);}
                else if(version==1 && length>=32) {scale=u32(bytes,payload+20,false);ticks=i64(bytes,payload+24);}else throw invalid();
                if(scale==0 || ticks<=0 || ticks>600L*scale) throw invalid();movie.duration=Math.round(ticks*1000d/scale);
            }
            if(kind.equals("tkhd") && parent.equals("trak")) {
                if(length<84) throw invalid();int width=(int)(u32(bytes,end-8,false)>>16),height=(int)(u32(bytes,end-4,false)>>16);
                if(width>0 && height>0) {dimensions(width,height);movie.width=width;movie.height=height;}
            }
            if(kind.equals("hdlr") && parent.equals("mdia")) {if(length<12) throw invalid();if(text(bytes,payload+8,4).equals("vide")) movie.video=true;}
            if(Set.of("moov","trak","mdia","minf","stbl","edts","dinf","mvex").contains(kind)) boxes(bytes,payload,end,depth+1,movie,kind);
            at=end;
        }
    }
    private static String text(byte[] bytes,int offset,int length) {if(offset<0 || offset+length>bytes.length) throw invalid();return new String(bytes,offset,length,StandardCharsets.ISO_8859_1);}
    private static long u32(byte[] bytes,int at,boolean little) {if(at<0 || bytes.length-at<4) throw invalid();return Integer.toUnsignedLong(ByteBuffer.wrap(bytes,at,4).order(little?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).getInt());}
    private static int u16(byte[] bytes,int at) {if(at<0 || bytes.length-at<2) throw invalid();return Short.toUnsignedInt(ByteBuffer.wrap(bytes,at,2).order(ByteOrder.LITTLE_ENDIAN).getShort());}
    private static long i64(byte[] bytes,int at) {if(at<0 || bytes.length-at<8) throw invalid();long value=ByteBuffer.wrap(bytes,at,8).getLong();if(value<0) throw invalid();return value;}
    private static void dimensions(int width,int height) {if(width<1 || height<1 || width>8192 || height>8192 || (long)width*height>MAX_PIXELS) throw new DomainException("ASSET_DIMENSIONS_EXCEEDED",400,"Images and video must be at most 8192 pixels per side and 16 megapixels");}
    private static void duration(long millis) {if(millis<1 || millis>600000) throw new DomainException("ASSET_DURATION_EXCEEDED",400,"Audio and video must contain 1 millisecond to 10 minutes");}
    private static DomainException invalid() {return new DomainException("ASSET_FORMAT_INVALID",400,"File bytes do not match a complete supported media structure");}
}
