

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.stream.ChunkedFile;
import io.netty.util.CharsetUtil;

import javax.activation.MimetypesFileTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

import java.util.regex.Pattern;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.LOCATION;
import static io.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpHeaders.isKeepAlive;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;

public class HttpFileServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final String url;

    private static final Pattern ALLOWED_FILE_NAME = Pattern
            .compile("[A-Za-z0-9][-_A-Za-z0-9\\.]*");


    private static final Pattern INSECURE_URI = Pattern.compile(".*[<>&\"].*");

    public HttpFileServerHandler(String url) {
        this.url = url;
    }

    @Override
    public void messageReceived(ChannelHandlerContext channelHandlerContext, FullHttpRequest fullHttpRequest) throws Exception {
        System.out.println("messageReceived.");
        //请求无法解析 返回400
        if(!fullHttpRequest.getDecoderResult().isSuccess()){
            sendError(channelHandlerContext,BAD_REQUEST);
            System.out.println("BAD_REQUEST.");
            return;
        }

        //只支持get方法
        if(fullHttpRequest.getMethod()!=HttpMethod.GET){
            sendError(channelHandlerContext,METHOD_NOT_ALLOWED);
            System.out.println("METHOD_NOT_ALLOWED.");
            return;
        }

        final String uri = fullHttpRequest.getUri();
        //格式化URL 并且获取路径
        final String path = sanitizeUri(uri);
        if(path==null){
            sendError(channelHandlerContext,FORBIDDEN);
            System.out.println("FORBIDDEN.");
            return;
        }
        File file = new File(path);
        //如果文件不可访问或者文件不存在
        if(file.isHidden()||!file.exists()){
            System.out.println("NOT_FOUND.");
            sendError(channelHandlerContext,NOT_FOUND);
            return;
        }

        //如果是目录
        if(file.isDirectory()){
            //1. 以/结尾就列出所有文件
            if (uri.endsWith("/")) {

                sendListing(channelHandlerContext, file);
            } else {
                //2. 否则自动+/
                sendRedirect(channelHandlerContext, uri + '/');
            }
            return;
        }

        if(!file.isFile()){
            sendError(channelHandlerContext,FORBIDDEN);
            return;
        }

        RandomAccessFile randomAccessFile = null;
        try {
            randomAccessFile = new RandomAccessFile(file,"r");
        } catch (FileNotFoundException e) {
            sendError(channelHandlerContext, NOT_FOUND);
            return;
        }

        long fileLength = randomAccessFile.length();
        //创建一个默认的HTTP响应
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1,OK);
        //设置content length
        setContentLength(response, fileLength);
        //设置content type
        setContentTypeHeader(response, file);
        //如果request中有KEEP ALIVE信息
        if(isKeepAlive(fullHttpRequest)){
            response.headers().set(CONNECTION,KEEP_ALIVE);

        }

        channelHandlerContext.write(response);
        ChannelFuture channelFuture;
        //通过Netty的ChunkedFile对象直接将文件写入发送到缓冲区中
        channelFuture = channelHandlerContext.write(new ChunkedFile(randomAccessFile,0,fileLength,8192),
                channelHandlerContext.newProgressivePromise());
        channelFuture.addListener(new ChannelProgressiveFutureListener() {
            public void operationProgressed(ChannelProgressiveFuture channelProgressiveFuture, long progress, long total) throws Exception {
                if(total<0){
                    System.out.println("Transfer progress: " + progress);
                }else {
                    System.out.println("Transfer progress: " + progress + " / "
                            + total);
                }
            }

            public void operationComplete(ChannelProgressiveFuture channelProgressiveFuture) throws Exception {
                System.out.println("Transfer complete.");


            }
        });
        System.out.println("````");
        ChannelFuture lastContentFuture = channelHandlerContext.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        //如果不支持keep-Alive，服务器端主动关闭请求
        if (!isKeepAlive(fullHttpRequest)) {
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }

    }



    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        //cause.printStackTrace();
        if (ctx.channel().isActive()) {
            sendError(ctx, INTERNAL_SERVER_ERROR);
            ctx.close();
        }
    }

    private static void sendListing(ChannelHandlerContext ctx,File dir){
        System.out.println("sendListing");
        // 设置响应对象
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1,OK);
        // 响应头
        response.headers().set(CONTENT_TYPE,"text/html;charset=UTF-8");
        // 追加文本内容
        StringBuilder buf = new StringBuilder();
        String dirPath = dir.getPath();
        buf.append("<!DOCTYPE html>\r\n");
        buf.append("<html><head><title>");
        buf.append(dirPath);
        buf.append(" 目录：");
        buf.append("</title></head><body>\r\n");
        buf.append("<h3>");
        buf.append(dirPath).append(" 目录：");
        buf.append("</h3>\r\n");
        buf.append("<ul>");
        buf.append("<li>链接：<a href=\"../\">..</a></li>\r\n");
        for (File f:dir.listFiles()){
            if(f.isHidden()||!f.canRead()){
                continue;
            }
            String name = f.getName();
            if(!ALLOWED_FILE_NAME.matcher(name).matches()){
                continue;
            }
            buf.append("<li>链接：<a href=\"");
            buf.append(name);
            buf.append("\">");
            buf.append(name);
            buf.append("</a></li>\r\n");

        }
        buf.append("</ul></body></html>\r\n");
        ByteBuf buffer = Unpooled.copiedBuffer(buf, CharsetUtil.UTF_8);
        response.content().writeBytes(buffer);
        buffer.release();
       System.out.println(buf);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);


    }

    private String sanitizeUri(String uri){
        try {
            uri = URLDecoder.decode(uri,"UTF-8");
        } catch (UnsupportedEncodingException e) {
            try {
                uri = URLDecoder.decode(uri, "ISO-8859-1");
            } catch (UnsupportedEncodingException e1) {
                e1.printStackTrace();
                throw  new Error();
            }
        }
        // 对uri进行细粒度判断：4步验证操作
        // step 1 基础验证
        if(!uri.startsWith(url)){
            return null;
        }
        // step 2 基础验证
        if(!uri.startsWith("/")){
            return null;
        }
        // step 3 将文件分隔符替换为本地操作系统的文件路径分隔符
        uri = uri.replace('/', File.separatorChar);
        // step 4 二次验证合法性
        if(uri.contains(File.pathSeparator+'.')
                ||uri.contains('.'+File.separator)
                ||uri.startsWith(".")
                ||uri.startsWith(".")
                ||INSECURE_URI.matcher(uri).matches()){
            return null;
        }
        //当前工程所在目录 + URI构造绝对路径进行返回
        return System.getProperty("user.dir")+ File.separator+uri;
    }

    private static void sendError(ChannelHandlerContext ctx,
                                  HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1,
                status, Unpooled.copiedBuffer("Failure: " + status.toString()
                + "\r\n", CharsetUtil.UTF_8));
        System.out.println("sendError.");
        response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener
                .CLOSE);
    }

    private static void setContentTypeHeader(HttpResponse response, File file){
        MimetypesFileTypeMap mimetypesFileTypeMap = new MimetypesFileTypeMap();
        response.headers().set(CONTENT_TYPE,mimetypesFileTypeMap.getContentType(file.getPath()));
    }

    private static void sendRedirect(ChannelHandlerContext ctx,String newUri){
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1,FOUND);
        response.headers().set(LOCATION,newUri);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

}
