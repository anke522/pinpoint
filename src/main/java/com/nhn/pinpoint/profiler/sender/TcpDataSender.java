package com.nhn.pinpoint.profiler.sender;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.thrift.TBase;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nhn.pinpoint.rpc.Future;
import com.nhn.pinpoint.rpc.FutureListener;
import com.nhn.pinpoint.rpc.PinpointSocketException;
import com.nhn.pinpoint.rpc.ResponseMessage;
import com.nhn.pinpoint.rpc.client.PinpointSocket;
import com.nhn.pinpoint.rpc.client.PinpointSocketFactory;
import com.nhn.pinpoint.thrift.dto.TResult;
import com.nhn.pinpoint.thrift.io.HeaderTBaseDeserializer;
import com.nhn.pinpoint.thrift.io.HeaderTBaseSerDesFactory;
import com.nhn.pinpoint.thrift.io.HeaderTBaseSerializer;

/**
 * @author emeroad
 * @author koo.taejin
 */
public class TcpDataSender extends AbstractDataSender implements EnhancedDataSender {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    static {
        // preClassLoad
        ChannelBuffers.buffer(2);
    }

    private final PinpointSocketFactory pinpointSocketFactory;
    private PinpointSocket socket;
    private final int connectRetryCount = 3;

    private final AtomicBoolean fireState = new AtomicBoolean(false);

    private final WriteFailFutureListener writeFailFutureListener;


    private final HeaderTBaseSerializer serializer = HeaderTBaseSerDesFactory.getSerializer(HeaderTBaseSerDesFactory.DEFAULT_SAFETY_GURANTEED_MAX_SERIALIZE_DATA_SIZE);

    private final RetryQueue retryQueue = new RetryQueue();

    private AsyncQueueingExecutor<Object> executor;


    public TcpDataSender(String host, int port) {

        pinpointSocketFactory = new PinpointSocketFactory();
        pinpointSocketFactory.setTimeoutMillis(1000 * 5);
        writeFailFutureListener = new WriteFailFutureListener(logger, "io write fail.", host, port);
        connect(host, port);

        this.executor = createAsyncQueueingExecutor(1024 * 5, "Pinpoint-TcpDataExecutor");
    }
    
    @Override
    public boolean send(TBase<?, ?> data) {
        return executor.execute(data);
    }

    @Override
    public boolean request(TBase<?, ?> data) {
        return this.request(data, 3);
    }

    @Override
    public boolean request(TBase<?, ?> data, int retryCount) {
    	RequestMarker message = new RequestMarker(data, retryCount);
        return executor.execute(message);
    }

    @Override
    public void stop() {
        executor.stop();
        socket.close();
        pinpointSocketFactory.release();
    }

    @Override
    protected void sendPacket(Object message) {
        try {
        	if (message instanceof TBase) {
        		byte[] copy = serialize(serializer, (TBase) message);
                if (copy == null) {
                    return;
                }
                doSend(copy);
        	} else if (message instanceof RequestMarker) {
        		TBase tBase = ((RequestMarker) message).getTBase();
        		int retryCount = ((RequestMarker) message).getRetryCount();
        		
        		byte[] copy = serialize(serializer, tBase);
                if (copy == null) {
                    return;
                }
                doRequest(copy, retryCount, tBase);
        	} else {
                logger.error("sendPacket fail. invalid dto type:{}", message.getClass());
                return;
        	}
        } catch (Exception e) {
            // 일단 exception 계층이 좀 엉터리라 Exception으로 그냥 잡음.
            logger.warn("tcp send fail. Caused:{}", e.getMessage(), e);
        }
    }

    private void connect(String host, int port) {
        for (int i = 0; i < connectRetryCount; i++) {
            try {
                this.socket = pinpointSocketFactory.connect(host, port);
                logger.info("tcp connect success:{}/{}", host, port);
                return;
            } catch (PinpointSocketException e) {
                logger.warn("tcp connect fail:{}/{} try reconnect, retryCount:{}", host, port, i);
            }
        }
        logger.warn("change background tcp connect mode  {}/{} ", host, port);
        this.socket = pinpointSocketFactory.scheduledConnect(host, port);
    }

    private void doSend(byte[] copy) {
        Future write = this.socket.sendAsync(copy);
        write.setListener(writeFailFutureListener);
    }

    private void doRequest(final byte[] requestPacket, final int retryCount, final Object targetClass) {
        // 리팩토링 필요.
        final Future<ResponseMessage> response = this.socket.request(requestPacket);
        response.setListener(new FutureListener<ResponseMessage>() {
            @Override
            public void onComplete(Future<ResponseMessage> future) {
                if (future.isSuccess()) {
            		// caching해야 될려나?
                	HeaderTBaseDeserializer deserializer = HeaderTBaseSerDesFactory.getDeserializer();
                    TBase<?, ?> response = deserialize(deserializer, future.getResult());
                    if (response instanceof TResult) {
                        TResult result = (TResult) response;
                        if (result.isSuccess()) {
                            logger.debug("result success");
                        } else {
                            logger.warn("request fail. clazz:{} Caused:{}", targetClass, result.getMessage());
                            retryRequest(requestPacket, retryCount, targetClass.getClass().getSimpleName());
                        }
                    } else {
                        logger.warn("Invalid ResponseMessage. {}", response);
//                         response가 이상하게 오는 케이스는 재전송 케이스가 아니고 로그를 통한 정확한 원인 분석이 필요한 케이스이다.
//                        null이 떨어질수도 있음.
//                        retryRequest(requestPacket);
                    }
                } else {
                    logger.warn("request fail. clazz:{} Caused:{}", targetClass, future.getCause().getMessage(), future.getCause());
                    retryRequest(requestPacket, retryCount, targetClass.getClass().getSimpleName());
                }
            }
        });
    }

    private void retryRequest(byte[] requestPacket, int retryCount, final String className) {
        RetryMessage retryMessage = new RetryMessage(retryCount, requestPacket);
        retryQueue.add(retryMessage);
        if (fireTimeout()) {
            pinpointSocketFactory.newTimeout(new TimerTask() {
                @Override
                public void run(Timeout timeout) throws Exception {
                    while(true) {
                        RetryMessage retryMessage = retryQueue.get();
                        if (retryMessage == null) {
                            // 동시성이 약간 안맞을 가능성 있는거 같기는 하나. 크게 문제 없을거 같아서 일단 패스.
                            fireComplete();
                            return;
                        }
                        int fail = retryMessage.fail();
                        doRequest(retryMessage.getBytes(), fail, className);
                    }
                }
            }, 1000 * 10, TimeUnit.MILLISECONDS);
        }
    }


    private boolean fireTimeout() {
        if (fireState.compareAndSet(false, true)) {
            return true;
        } else {
            return false;
        }
    }

    private void fireComplete() {
        logger.debug("fireComplete");
        fireState.compareAndSet(true, false);
    }

}
