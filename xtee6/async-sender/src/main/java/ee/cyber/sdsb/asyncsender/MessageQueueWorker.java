package ee.cyber.sdsb.asyncsender;

import java.io.InputStream;
import java.util.Date;

import lombok.extern.slf4j.Slf4j;

import ee.cyber.sdsb.asyncdb.SendingCtx;
import ee.cyber.sdsb.asyncdb.messagequeue.MessageQueue;
import ee.cyber.sdsb.asyncdb.messagequeue.QueueInfo;
import ee.cyber.sdsb.common.message.SoapMessageImpl;

import static ee.cyber.sdsb.common.ErrorCodes.translateException;

@Slf4j
class MessageQueueWorker implements Runnable {

    private static final int UPDATE_INTERVAL = 1000; // ms

    private final MessageQueue queue;

    private volatile boolean running;

    MessageQueueWorker(MessageQueue queue) {
        if (queue == null) {
            throw new IllegalArgumentException("Queue must not be null");
        }

        this.queue = queue;
        this.running = true;
    }

    @Override
    public void run() {
        try {
            Date nextAttempt = getNextAttempt();
            while (running) {
                if (nextAttempt == null) {
                    // No more messages, this worker can go home!
                    break;
                }

                trySendNextMessage(nextAttempt);

                sleep();

                nextAttempt = getNextAttempt();
            }
        } finally {
            running = false;
        }
    }

    boolean isRunning() {
        return running;
    }

    Date getNextAttempt() {
        QueueInfo queueInfo;
        try {
            queueInfo = queue.getQueueInfo();
        } catch (Exception e) {
            // Failure to get queue info is fatal!
            log.error("Failed to get QueueInfo", e);
            return null;
        }

        Date nextAttempt = queueInfo.getNextAttempt();
        log.trace("getNextAttempt(): {}", nextAttempt);
        return nextAttempt;
    }

    void sleep() {
        try {
            Thread.sleep(UPDATE_INTERVAL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void trySendNextMessage(Date nextAttempt) {
        log.trace("trySendNextMessage({})", nextAttempt);

        if (new Date().after(nextAttempt)) {
            log.trace("Start sending message at {}", nextAttempt);
            doSendNextMessage();
        }
    }

    void doSendNextMessage() {
        log.trace("doSendNextMessage()");

        SendingCtx sendingCtx = null;
        try {
            sendingCtx = queue.startSending();
            if (sendingCtx == null) {
                log.trace("Did not get SendingCtx, assuming no more messages");
                return;
            }
        } catch (Exception e) {
            log.error("Failed to get SendingCtx", e);
            return;
        }

        SoapMessageImpl response = null;
        try {
            response = sendMessage(sendingCtx);
            sendingCtx.success(response != null ? response.getXml() : "");
            log.trace("Message successfully sent!");
        } catch (Exception e) {
            log.error("Failed to send message", e);

            String faultCode = translateException(e).getFaultCode();
            try {
                String result = response != null
                        ? response.getXml() : e.toString();
                sendingCtx.failure(faultCode, result);
            } catch (Exception e1) {
                log.error("Error when calling sendingCtx.failure(" + faultCode
                        + ")", e1);
            }
        }
    }

    SoapMessageImpl sendMessage(SendingCtx sendingCtx) throws Exception {
        String contentType = sendingCtx.getContentType();
        InputStream message = sendingCtx.getInputStream();
        return ProxyClient.getInstance().send(contentType, message);
    }

}
