package ee.cyber.sdsb.asyncdb;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ee.cyber.sdsb.asyncdb.messagequeue.MessageQueue;
import ee.cyber.sdsb.asyncdb.messagequeue.QueueInfo;
import ee.cyber.sdsb.asyncdb.messagequeue.RequestInfo;
import ee.cyber.sdsb.common.SystemProperties;
import ee.cyber.sdsb.common.identifier.ClientId;
import ee.cyber.sdsb.common.identifier.ServiceId;
import ee.cyber.sdsb.common.message.SoapMessageConsumer;
import ee.cyber.sdsb.common.message.SoapMessageImpl;
import ee.cyber.sdsb.common.util.SystemMetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * This test is supposed to exercise all the functionality related to async-db.
 * Certain steps are supposed to be run in consecutive manner and results of
 * every step are verified.
 */
public class AsyncDBIntegrationTest {
    private static final String CORRUPT_DB_DIR = "build/db_corrupt";

    private static final Logger LOG = LoggerFactory
            .getLogger(AsyncDBIntegrationTest.class);

    private static final int TOTAL_STEPS = 10;

    private static final String LAST_SEND_RESULT_SUCCESS = "LAST SEND RESULT SUCCESS";
    private static final String LAST_SEND_RESULT_FAILURE = "LAST SEND RESULT FAILURE";

    private static final List<String> successfulSteps = new ArrayList<>();
    private static MessageQueue queue;

    static {
        AsyncDBTestUtil.setTestenvProps();
        ClientId provider = AsyncDBTestUtil.getProvider();
        try {
            queue = AsyncDB.getMessageQueue(provider);
        } catch (Exception e) {
            LOG.error("Could not get message queue: ", e);
            throw new IntegrationTestFailedException(
                    "Could not create  message queue for service" + provider);
        }
    }

    /**
     *
     *
     * @param args
     *            - use 'preservedb' to retain directory structure for further
     *            investigation
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {
        File provider = new File(AsyncDBTestUtil.getProviderDirPath());
        File logFile = new File(AsyncDBTestUtil.getAsyncLogFilePath());
        if (provider.exists() || logFile.exists()) {
            throw new IntegrationTestFailedException(
                    "Directory '"
                            + AsyncDBTestUtil.DB_FILEPATH
                            + "' and file '"
                            + AsyncDBTestUtil.LOG_FILEPATH
                            + "' must not be present in the beginning of integration test, delete it!");
        }

        File logDir = logFile.getParentFile();
        logDir.mkdirs();

        boolean preserveDB = false;
        if (args.length > 0) {
            preserveDB = "preservedb".equalsIgnoreCase(args[0]);
            LOG.warn("Preserving DB file tree after test, be sure to remove them later by yourself!");
        }

        long freeFileDescriptorsAtBeginning = SystemMetrics
                .getFreeFileDescriptorCount();

        try {

            addRequestToNonExistentProvider();
            addRequestToExistentProvider();
            markSecondRequestAsRemoved();
            restoreSecondRequest();
            getAllMessageQueues();
            sendFirstRequestSuccessfully();
            sendSecondRequestUnsuccessfully();
            resetSendCountOfSecondRequest();
            skipNotSendingRequest();
            revertWritingFailure();

            // Test cases from real life
            removeCorruptRequestAndSendNext();
        } finally {
            validateFileDescriptors(freeFileDescriptorsAtBeginning);

            if (!preserveDB) {
                FileUtils.deleteDirectory(new File(AsyncDBTestUtil
                        .getProviderDirPath()));
                FileUtils.deleteDirectory(logDir);
            }
        }

        LOG.info("Integration test of ASYNC-DB accomplished successfully.");
    }

    // Test cases - start

    private static void addRequestToNonExistentProvider()
            throws Exception {
        LOG.info("TEST 1: addRequestToNonExistentProvider - STARTED");

        SoapMessageImpl requestMessage = AsyncDBTestUtil.getFirstSoapRequest();

        WritingCtx writingCtx = queue.startWriting();
        writingCtx.getConsumer().soap(requestMessage);
        writingCtx.commit();

        QueueInfo expectedQueueInfo = new QueueInfo(
                AsyncDBTestUtil.getProvider(), 1, 0, null, 0, "", null, "");
        validateQueueInfo(expectedQueueInfo);

        int requestNo = 0;

        RequestInfo expectedRequestInfo = getFirstRequest();
        validateRequestInfo(expectedRequestInfo);

        validateSavedMessage(requestNo, Arrays.asList(requestMessage.getXml()));

        validateContentType(requestNo);

        successfulSteps.add("addRequestToNonExistentProvider");

        LOG.info("TEST 1: addRequestToNonExistentProvider - FINISHED");
    }

    private static void addRequestToExistentProvider() throws Exception {
        LOG.info("TEST 2: addRequestToExistentProvider - STARTED");

        SoapMessageImpl requestMessage = AsyncDBTestUtil.getSecondSoapRequest();

        String contentType = "application/json";
        String attachmentContent = "{name:'Vitali'}";
        InputStream attachmentIS = IOUtils.toInputStream(attachmentContent);

        WritingCtx writingCtx = queue.startWriting();
        SoapMessageConsumer consumer = writingCtx.getConsumer();
        consumer.soap(requestMessage);
        consumer.attachment(contentType, attachmentIS, null);
        writingCtx.commit();

        QueueInfo expectedQueueInfo = new QueueInfo(
                AsyncDBTestUtil.getProvider(), 2, 0, null, 0, "", null, "");
        validateQueueInfo(expectedQueueInfo);

        int requestNo = 1;

        RequestInfo expectedRequestInfo = getSecondRequest();
        validateRequestInfo(expectedRequestInfo);

        validateSavedMessage(requestNo,
                Arrays.asList(requestMessage.getXml(), attachmentContent));

        validateContentType(requestNo);

        successfulSteps.add("addRequestToExistentProvider");

        LOG.info("TEST 2: addRequestToExistentProvider - FINISHED");
    }

    private static void markSecondRequestAsRemoved() throws Exception {
        LOG.info("TEST 3: markSecondRequestAsRemoved - STARTED");

        String id = "0987654321";

        queue.markAsRemoved(id);

        RequestInfo expectedRequestInfo = new RequestInfo(
                1, id, new Date(), new Date(), getTestClient(), "EE37702211234",
                        getSecondAsyncService());
        validateRequestInfo(expectedRequestInfo);

        successfulSteps.add("markSecondRequestAsRemoved");

        LOG.info("TEST 3: markSecondRequestAsRemoved - FINISHED");
    }

    private static void restoreSecondRequest() throws Exception {
        LOG.info("TEST 4: restoreSecondRequest - STARTED");

        String id = "0987654321";

        queue.restore(id);

        RequestInfo expectedRequestInfo = getSecondRequest();
        validateRequestInfo(expectedRequestInfo);

        successfulSteps.add("restoreSecondRequest");

        LOG.info("TEST 4: restoreSecondRequest - FINISHED");
    }

    private static void getAllMessageQueues() throws Exception {
        LOG.info("TEST 5: getAllMessageQueues - STARTED");

        List<MessageQueue> allMessageQueues = AsyncDB.getMessageQueues();
        int expectedSize = 1;

        if (allMessageQueues.size() != expectedSize) {
            throw new IntegrationTestFailedException(
                    "Size of all message queues should be '" + expectedSize
                            + "', but is '" + allMessageQueues.size() + "'.");
        }

        successfulSteps.add("getAllMessageQueues");

        LOG.info("TEST 5: getAllMessageQueues - FINISHED");
    }

    private static void sendFirstRequestSuccessfully() throws Exception {
        LOG.info("TEST 6: sendFirstRequestSuccessfully - STARTED");

        String expectedSoap = AsyncDBTestUtil.getFirstSoapRequest()
                .getXml();

        SendingCtx sendingCtx = queue.startSending();

        String firstRequestContent = IOUtils.toString(sendingCtx
                .getInputStream());

        if (!StringUtils.contains(firstRequestContent, expectedSoap)) {
            throw new IntegrationTestFailedException(
                    "Message input stream does not contain expected content.");
        }

        validateContentType(0);

        // Between starting and committing request must be in state 'sending'.
        RequestInfo expectedRequestInfo = RequestInfo
                .markSending(getFirstRequest());
        validateRequestInfo(expectedRequestInfo);

        sendingCtx.success(LAST_SEND_RESULT_SUCCESS);

        QueueInfo expectedQueueInfo = new QueueInfo(
                AsyncDBTestUtil.getProvider(), 1, 1, new Date(), 0,
                "1234567890", new Date(), LAST_SEND_RESULT_SUCCESS);
        validateQueueInfo(expectedQueueInfo);

        int expectedRequestsSize = 1;
        int actualRequestsSize = queue.getRequests().size();

        if (actualRequestsSize != expectedRequestsSize) {
            throw new IntegrationTestFailedException("There must be "
                    + expectedRequestsSize
                    + " request(s) under provider, but there is "
                    + actualRequestsSize);
        }

        successfulSteps.add("sendFirstRequestSuccessfully");

        LOG.info("TEST 6: sendFirstRequestSuccessfully - FINISHED");
    }

    private static void sendSecondRequestUnsuccessfully() throws Exception {
        LOG.info("TEST 7: sendSecondRequestUnsuccessfully - STARTED");

        SendingCtx sendingCtx = queue.startSending();

        String expectedSoap = AsyncDBTestUtil.getSecondSoapRequest().getXml();
        String secondRequestContent = IOUtils.toString(sendingCtx
                .getInputStream());

        if (!StringUtils.contains(secondRequestContent, expectedSoap)) {
            throw new IntegrationTestFailedException(
                    "Message input stream does not contain expected content.");
        }

        sendingCtx.failure("ERROR", LAST_SEND_RESULT_FAILURE);

        QueueInfo expectedQueueInfo = new QueueInfo(
                AsyncDBTestUtil.getProvider(), 1, 1, new Date(),
                1, "1234567890", new Date(), LAST_SEND_RESULT_FAILURE);
        validateQueueInfo(expectedQueueInfo);

        RequestInfo expectedRequestInfo = getSecondRequest();
        validateRequestInfo(expectedRequestInfo);

        successfulSteps.add("sendSecondRequestUnsuccessfully");

        LOG.info("TEST 7: sendSecondRequestUnsuccessfully - FINISHED");
    }

    private static void resetSendCountOfSecondRequest() throws Exception {
        LOG.info("TEST 8: resetSendCountOfSecondRequest - STARTED");

        queue.resetCount();

        QueueInfo expectedQueueInfo = new QueueInfo(
                AsyncDBTestUtil.getProvider(), 1, 1, new Date(),
                0, "1234567890", new Date(), LAST_SEND_RESULT_FAILURE);
        validateQueueInfo(expectedQueueInfo);

        successfulSteps.add("resetSendCountOfSecondRequest");

        LOG.info("TEST 8: resetSendCountOfSecondRequest - FINISHED");
    }

    /**
     * Handles situation when the only request in the queue is marked as not
     * sending. In this case it should be deleted and null sending ctx should be
     * returned.
     *
     * @throws Exception
     */
    private static void skipNotSendingRequest() throws Exception {
        LOG.info("TEST 9: skipNotSendingRequest - STARTED");
        String id = "0987654321";

        queue.markAsRemoved(id);

        SendingCtx sendingCtx = queue.startSending();
        if (sendingCtx != null) {
            throw new IntegrationTestFailedException(
                    "Should return empty sending ctx if no messages are in queue!");
        }

        QueueInfo expectedQueueInfo = new QueueInfo(
                AsyncDBTestUtil.getProvider(), 0, 0, new Date(),
                0, "1234567890", new Date(), LAST_SEND_RESULT_FAILURE);
        validateQueueInfo(expectedQueueInfo);

        if (!queue.getRequests().isEmpty()) {
            throw new IntegrationTestFailedException(
                    "Queue should contain no requests at this point!");
        }

        validateAsyncLog();

        successfulSteps.add("skipNotSendingRequest");

        LOG.info("TEST 9: skipNotSendingRequest - FINISHED");
    }

    private static void revertWritingFailure() throws Exception {
        LOG.info("TEST 10: revertWritingFailure - STARTED");
        QueueInfo initialQueueInfo = queue.getQueueInfo();

        SoapMessageImpl requestMessage = AsyncDBTestUtil.getFirstSoapRequest();

        WritingCtx writingCtx = queue.startWriting();
        writingCtx.getConsumer().soap(requestMessage);
        writingCtx.rollback();

        validateQueueInfo(initialQueueInfo);

        successfulSteps.add("revertWritingFailure");

        LOG.info("TEST 10: revertWritingFailure - FINISHED");
    }

    private static void removeCorruptRequestAndSendNext() throws Exception {
        LOG.info("TEST 11: removeCorruptRequestAndSendNext - STARTED");

        // Given
        setUpCorruptDb();

        // When
        SendingCtx ctx = queue.startSending();
        ctx.success(LAST_SEND_RESULT_SUCCESS);

        // Then
        QueueInfo queueInfo = queue.getQueueInfo();

        assertEquals(1, queueInfo.getRequestCount());
        assertEquals(2, queueInfo.getFirstRequestNo());

        File corruptMsgDir =
                new File(CORRUPT_DB_DIR + File.separator
                        + "59ad26a55333e38b928ffd7aef6bc020" + File.separator
                        + "CORRUPT_0");

        assertTrue(corruptMsgDir.exists());

        LOG.info("TEST 11: removeCorruptRequestAndSendNext - FINISHED");
    }
    // Test cases - end

    private static void setUpCorruptDb() throws Exception {
        File corruptDbDir = new File(CORRUPT_DB_DIR);
        corruptDbDir.mkdir();

        FileUtils.copyDirectory(
                new File("src/test/resources/db_corrupt"),
                corruptDbDir);

        System.setProperty(SystemProperties.ASYNC_DB_PATH, CORRUPT_DB_DIR);

        ClientId provider = ClientId.create("EE", "GOV", "XTS4CLIENT");
        queue = AsyncDB.getMessageQueue(provider);
    }

    private static void validateFileDescriptors(
            long freeFileDescriptorsAtBeginning) {
        long freeFileDescriptorsAtTheEnd = SystemMetrics
                .getFreeFileDescriptorCount();

        long leakedFileDescriptors =
                freeFileDescriptorsAtBeginning
                        - freeFileDescriptorsAtTheEnd;

        // One file descriptor is taken by native FileDispatcherImpl.init()
        // at the beginning, so this seems to be allowed.
        if (leakedFileDescriptors > 1) {
            throw new RuntimeException("Integration test failed, " +
                    leakedFileDescriptors + " file descriptors leaked.");
        }
    }

    private static void validateQueueInfo(QueueInfo expected) {
        QueueInfo actual = null;
        try {
            actual = queue.getQueueInfo();
        } catch (Exception e) {
            throw new IntegrationTestFailedException(e.getMessage());
        }

        if (!areProvidersEqual(expected, actual)) {
            LOG.error(
                    "Provider was supposed to be '{}', but was actually {}",
                    expected, actual);
            throw new IntegrationTestFailedException(
                    "Provider invalid");
        }
    }

    private static void validateRequestInfo(RequestInfo expected) {
        RequestInfo actual = null;
        try {
            int orderNoInQueue = expected.getOrderNo()
                    - queue.getQueueInfo().getFirstRequestNo();
            actual = queue.getRequests().get(orderNoInQueue);
        } catch (Exception e) {
            LOG.error("Getting correct request failed:", e);
            throw new IntegrationTestFailedException(e.getMessage());
        }

        if (!areRequestsEqual(expected, actual)) {
            LOG.error(
                    "Request was supposed to be '{}', but was actually {}",
                    expected, actual);
            throw new IntegrationTestFailedException(
                    "Request is invalid");
        }
    }

    private static void validateSavedMessage(int requestNo,
            List<String> itemsToBeContained)
            throws Exception {
        try {
            String filePath = getMessageDetailsFilePath(requestNo,
                    MessageQueue.MESSAGE_FILE_NAME);
            LOG.debug("Inspecting SOAP message on file path: '{}'", filePath);

            String fileContent = FileUtils.readFileToString(new File(filePath),
                    StandardCharsets.UTF_8);
            LOG.debug("File content is: '{}'", fileContent);

            for (String item : itemsToBeContained) {
                if (!StringUtils.contains(fileContent, item)) {
                    throw new IntegrationTestFailedException(
                            "Saved message does not contain expected item '"
                                    + item + "'.");
                }
            }
        } catch (FileNotFoundException e) {
            throw new IntegrationTestFailedException(e.getMessage());
        }
    }

    /**
     * Checks only format correctness, logging more thoroughly tested in
     * respective unit test.
     *
     * @throws IOException
     */
    private static void validateAsyncLog() throws IOException {
        List<String> logFileLines = FileUtils.readLines(new File(
                AsyncDBTestUtil.getAsyncLogFilePath()), StandardCharsets.UTF_8);

        int expectedLineCount = 3;
        if (expectedLineCount != logFileLines.size()) {
            throw new IntegrationTestFailedException(
                    "Async-log file should have " + expectedLineCount
                            + "' lines, but has " + logFileLines.size()
                            + " lines.");
        }

        int lineIndex = 0;
        for (String logFileLine : logFileLines) {
            String[] fields = logFileLine.split(""
                    + AsyncLogWriter.FIELD_SEPARATOR);
            if (fields.length != AsyncDBTestUtil.LOG_FILE_FIELDS) {
                throw new IntegrationTestFailedException("Log file nr "
                        + lineIndex + " has " + fields.length
                        + " fields, but must have "
                        + AsyncDBTestUtil.LOG_FILE_FIELDS);
            }
            lineIndex++;
        }
    }

    private static String getMessageDetailsFilePath(int requestNo,
            String fileName) throws Exception {
        return AsyncDBTestUtil.getProviderDirPath()
                + File.separator + requestNo + File.separator + fileName;
    }

    private static void validateContentType(int requestNo) throws Exception {
        String filePath = getMessageDetailsFilePath(requestNo,
                MessageQueue.CONTENT_TYPE_FILE_NAME);

        try {
            String contentType = FileUtils.readFileToString(new File(filePath),
                    StandardCharsets.UTF_8);

            if (StringUtils.isBlank(contentType)) {
                throw new IntegrationTestFailedException(
                        "Content type must not be blank!");
            }

            LOG.debug("Content type is: '{}'", contentType);
        } catch (IOException e) {
            LOG.error("Reading content type failed: ", e);
            throw new IntegrationTestFailedException(e.getMessage());
        }

    }

    private static boolean areProvidersEqual(QueueInfo expected,
            QueueInfo actual) {
        if (expected == null || actual == null) {
            return false;
        }

        return expected.getRequestCount() == actual.getRequestCount()
                && (expected.getFirstRequestNo() == actual.getFirstRequestNo())
                && areDatesEquivalent(expected.getLastSentTime(),
                        actual.getLastSentTime())
                && (expected.getFirstRequestSendCount()
                    == actual.getFirstRequestSendCount())
                && StringUtils.equals(expected.getLastSuccessId(),
                        actual.getLastSuccessId())
                && areDatesEquivalent(expected.getLastSuccessTime(),
                        actual.getLastSuccessTime())
                && StringUtils.equals(expected.getLastSendResult(),
                        actual.getLastSendResult());
    }

    private static boolean areRequestsEqual(RequestInfo expected,
            RequestInfo actual) {
        if (expected == null || actual == null) {
            return false;
        }

        return StringUtils.equals(expected.getId(), actual.getId())
                && areDatesEquivalent(expected.getReceivedTime(),
                        actual.getReceivedTime())
                && areDatesEquivalent(expected.getRemovedTime(),
                        actual.getRemovedTime())
                && expected.getSender().equals(actual.getSender())
                && StringUtils.equals(expected.getUser(), actual.getUser())
                && expected.getService().equals(actual.getService())
                && (expected.isSending() == actual.isSending());
    }

    /**
     * During validation we assume that expected date is never before actual.
     *
     * @param expected
     * @param actual
     * @return
     */
    private static boolean areDatesEquivalent(Date expected, Date actual) {
        if (expected == null || actual == null) {
            return true;
        }

        return !expected.before(actual);
    }

    private static RequestInfo getFirstRequest() {
        ServiceId service = ServiceId.create("EE", "BUSINESS", "servicemember",
                null, "sendSomeAsyncStuff");
        return new RequestInfo(
                0, "1234567890", new Date(), null, getTestClient(),
                "EE37702211234", service);
    }

    private static RequestInfo getSecondRequest() {
        return new RequestInfo(1, "0987654321",
                new Date(), null, getTestClient(), "EE37702211234",
                getSecondAsyncService());
    }

    private static ServiceId getSecondAsyncService() {
        return ServiceId.create("EE", "BUSINESS", "servicemember",
                null, "anotherAsyncService");
    }

    private static ClientId getTestClient() {
        return ClientId.create("EE", "BUSINESS", "clientmember");
    }
    @SuppressWarnings("serial")
    private static class IntegrationTestFailedException extends
            RuntimeException {
        IntegrationTestFailedException(String message) {
            super(getErrorMessage(message));
        }

        private static String getErrorMessage(String message) {
            StringBuilder sb = new StringBuilder("Integration test failed: '");
            sb.append(message).append("' Successful steps: ")
                    .append(successfulSteps.toString());

            String completion = String.format(" (%d/%d)",
                    successfulSteps.size(), TOTAL_STEPS);
            sb.append(completion);
            return sb.toString();
        }
    }
}
