package com.chicm.cmraft.rpc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.chicm.cmraft.protobuf.generated.RaftProtos.HeartBeatRequest;
import com.chicm.cmraft.protobuf.generated.RaftProtos.RequestHeader;
import com.chicm.cmraft.protobuf.generated.RaftProtos.ServerId;
import com.chicm.cmraft.protobuf.generated.RaftProtos.RaftService.BlockingInterface;
import com.google.protobuf.BlockingService;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.Message;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Message.Builder;

public class RaftRpcServer {
  static final Log LOG = LogFactory.getLog(RaftRpcServer.class);
  public final static int SERVER_PORT = 12888;
  private final static int DEFAULT_RPC_LISTEN_THREADS = 1;
  private final static int DEFAULT_BYTEBUFFER_SIZE = 1000;
  private SocketListener socketListener = null;
  private int rpcListenThreads = DEFAULT_RPC_LISTEN_THREADS;
  private RaftRpcService service = null;
  
  public static void main(String[] args) throws Exception {
    RaftRpcServer server = new RaftRpcServer(15);
    LOG.info("starting server");
    server.startRpcServer();
    
    final RaftRpcClient client = new RaftRpcClient();
    
    for(int i = 0; i < 10; i++) {
      new Thread(new Runnable() {
        public void run() {
          client.sendRequest();
        }
      }).start();
     }
  }
  
  RaftRpcServer (int nListenThreads) {
    socketListener = new SocketListener();
    rpcListenThreads = nListenThreads;
    service = new RaftRpcService();
  }
  
  public BlockingService getService() {
    return service.getService();
  }
  
  public boolean startRpcServer() {
    try {
      socketListener.start();
    } catch(IOException e) {
      e.printStackTrace(System.out);
      return false;
    }
    return true;
  }
  
  class SocketHandler implements CompletionHandler<AsynchronousSocketChannel, AsynchronousServerSocketChannel> {
    @Override
    public void completed(AsynchronousSocketChannel channel, AsynchronousServerSocketChannel serverChannel) {
      
      serverChannel.accept(serverChannel, this);
      LOG.info(String.format("SERVER[%d] accepted\n", Thread.currentThread().getId()));

      for(;;) {
        try {
          processRequest2(channel);
        } catch(Exception e) {
          e.printStackTrace(System.out);
        }
        LOG.debug("SERVER PROCESS FINISHED: " + channel);
      }
      //serverChannel.accept(serverChannel, this);
 
    }
    @Override
    public void failed(Throwable throwable, AsynchronousServerSocketChannel attachment) {
      throwable.printStackTrace(System.out);
    }
  }
  
  class SocketListener {
    public void start() throws IOException {
      AsynchronousChannelGroup group = AsynchronousChannelGroup.withFixedThreadPool(rpcListenThreads, 
          Executors.defaultThreadFactory());
      final AsynchronousServerSocketChannel serverChannel = AsynchronousServerSocketChannel.open(group)
          .bind(new InetSocketAddress(SERVER_PORT));
      serverChannel.accept(serverChannel, new SocketHandler());
      
      LOG.info("Server started");
    }
  }
  
  private void processRequest2(AsynchronousSocketChannel channel) 
      throws InterruptedException, ExecutionException {
    try {
      long curtime1 = System.currentTimeMillis();
      RpcUtils.parseRpcFromChannel(channel, getService());
      long curtime2 = System.currentTimeMillis();
      LOG.info("Parsing request takes: " + (curtime2-curtime1) + " ms");
      /*
      LOG.debug("entering process2...");
      ByteBuffer buf = ByteBuffer.allocate(DEFAULT_BYTEBUFFER_SIZE);
      int len = channel.read(buf).get();
      if(len <= 0) {
        LOG.error("server: connection closed by client");
        return;
      }
      LOG.debug("SERVER READ LEN:" + len);
      buf.flip();
      byte[] data = new byte[len];
      buf.get(data);
      
      CodedInputStream cis = CodedInputStream .newInstance(data);
      int messageSize = cis.readRawVarint32();
      int sizesize = cis.getTotalBytesRead();
      int nRemaining = messageSize + sizesize - len;
      
      if(nRemaining > 0) {
        ByteBuffer buf2 = ByteBuffer.allocate(nRemaining);
        if(channel.read(buf2).get() < nRemaining) {
          LOG.error("FAILED read data, remaining:" + nRemaining);
          return;
        }
        byte[] data2 = new byte[messageSize + sizesize];
        System.arraycopy(data, 0, data2, 0, len);
        
        buf2.flip();
        buf2.get(data2, len, nRemaining);
        data = data2;
      }
      int offset = sizesize; */
      //RequestHeader header = RequestHeader. newBuilder().mergeFrom(data, offset, headerSize ).build();
      //System.out.println("server: header parsed:" + header.toString());
      
      
    } catch (InterruptedException | ExecutionException e) {
      e.printStackTrace(System.out);
      throw e;
    } catch(Exception e) {
      e.printStackTrace(System.out);
    } 
  }
  
  private void processRequest(AsynchronousSocketChannel channel)
    throws InterruptedException, ExecutionException {
    try {
      ByteBuffer buf = ByteBuffer.allocate(DEFAULT_BYTEBUFFER_SIZE);
      int len;
        len = channel.read(buf).get();
      
    
      if(len <= 0) {
        LOG.debug("server: connection closed by client");
        return;
      }
      
      int offset = 0;
      LOG.debug("server received request, len:" + len);
      
      buf.flip();
      byte[] data = new byte[len];
      buf.get(data);
      
      CodedInputStream cis = CodedInputStream .newInstance(data, offset, len );
      int headerSize = cis.readRawVarint32();
      offset = cis.getTotalBytesRead();
      
      LOG.debug("server: headersize:" + headerSize);
      
      if(len <= offset) {
        buf.clear();
        len = channel.read(buf).get();
        data = new byte[len];
        buf.flip();
        buf.get(data);
        offset=0;
      }  
      
      RequestHeader header = RequestHeader. newBuilder().mergeFrom(data, offset, headerSize ).build();
      LOG.debug("server: header parsed:" + header.toString());
      
      offset += headerSize;
      if(len <= headerSize) {
        buf.clear();
        len = channel.read(buf).get();
        buf.flip();
        data = new byte[len];
        buf.get(data);
        offset=0;
      }

      MethodDescriptor md = getService().getDescriptorForType().findMethodByName(header.getRequestName());
      Builder builder = getService().getRequestPrototype(md).newBuilderForType();
      Message request = null;
      if (builder != null) {
        request = builder.mergeFrom(data, offset, len-offset).build();
        LOG.debug("server : request parsed:" + request.toString());
        Message response = getService().callBlockingMethod(md, null, request);
        LOG.debug("server method called:" + header.getRequestName());
        
        //System.out.println("Map:" + handle.getMap());
      }
 
      LOG.debug("RPC call done:" + request);
      
      } catch (InterruptedException | ExecutionException e) {
        // TODO Auto-generated catch block
          throw e;
      
      } catch(Exception e) {
      e.printStackTrace(System.out);
      }
 
  
  }
}