package org.verapdf.webapp.worker.listener;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClientResponseException;
import org.verapdf.webapp.jobservice.client.service.JobServiceClient;
import org.verapdf.webapp.jobservice.model.dto.JobDTO;
import org.verapdf.webapp.jobservice.model.entity.enums.JobStatus;
import org.verapdf.webapp.jobservice.model.entity.enums.Profile;
import org.verapdf.webapp.localstorageservice.client.service.LocalStorageServiceClient;
import org.verapdf.webapp.localstorageservice.model.dto.StoredFileDTO;
import org.verapdf.webapp.queueclient.listener.QueueListener;
import org.verapdf.webapp.queueclient.sender.QueueSender;
import org.verapdf.webapp.queueclient.util.QueueUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;

@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest
class ExecutableTaskWorkerTests {

	@Value("${verapdf.task.processing-limit}")
	private int processingLimit;

	@Autowired
	private ExecutableTaskWorker executableTaskWorker;

	@Autowired
	private File workingDir;

	@MockBean
	private JobServiceClient jobServiceClient;

	@MockBean
	private LocalStorageServiceClient localStorageServiceClient;

	@MockBean
	private QueueSender queueSender;

	@MockBean
	private QueueListener queueListener;

	@MockBean
	private QueueUtil queueUtil;

	@TempDir
	public static File tempDir;

	@AfterEach
	public void checkWorkingDirectory() {
		assertEquals(0, workingDir.listFiles().length);
		Mockito.verifyNoMoreInteractions(jobServiceClient, localStorageServiceClient, queueSender, queueUtil);
		Mockito.validateMockitoUsage();
	}

	//@Test
	void processTest() throws IOException {
		UUID jobId = UUID.fromString("397856eb-10fb-48c1-ad45-c90e418f070a");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource())
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		String expectedReportAsString = new String(getClass().getResourceAsStream(
				"/files/report-PDFA_1_A.json").readAllBytes(), StandardCharsets.UTF_8);
		Mockito.doReturn(createReportFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .saveFile(argThat(new FileAsJsonMatcher(expectedReportAsString)),
		                 eq("3dface64e5b27f5fc9b64941eb0b36bf"), eq(MediaType.APPLICATION_JSON));
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"397856eb-10fb-48c1-ad45-c90e418f070a\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);
		String expectedMessage
				= "{\"jobId\":\"397856eb-10fb-48c1-ad45-c90e418f070a\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"validationResultId\":\"d39da7b6-3665-4374-84e2-70c1e24e7029\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(localStorageServiceClient).saveFile(any(File.class),
		                                                   eq("3dface64e5b27f5fc9b64941eb0b36bf"), eq(MediaType.APPLICATION_JSON));
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void processMalformedPDFTest() throws IOException {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId, "veraPDFMalformedTestSuite.pdf",
		                                      "924365c6c67e35adf07db072fb6a4359", 115329))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource("/files/veraPDFMalformedTestSuite.pdf"))
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndKeepJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);


		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueUtil).rejectAndKeepJob(null, 1, jobId, fileId);
	}

	@Test
	void processMalformedPDFWithExceededProcessingLimitTest() throws IOException {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId, "veraPDFMalformedTestSuite.pdf",
		                                      "924365c6c67e35adf07db072fb6a4359", 115329))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource("/files/veraPDFMalformedTestSuite.pdf"))
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);
		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\"," +
				  "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"," +
				  "\"errorType\":\"PROCESSING_INTERNAL_ERROR\"," +
				  "\"errorMessage\":\"Couldn't parse stream\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void notParseableUUIDOnProcessTest() {
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, null, null);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"notParseableMessage\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);
		//just write log
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, null, null);
	}

	@Test
	void nullArgumentPassedOnProcessTest() {
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, null, null);

		executableTaskWorker.handleMessage(null, null, 1);
		//just write log
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, null, null);
	}

	@Test
	void nullableJobIdOnPassedExecutableTaskDTOOnProcessTest() {
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, null, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":null,"
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"" +
				  ",\"errorType\":\"INVALID_TASK_DATA_ERROR\"," +
				  "\"errorMessage\":\"Invalid task request: " +
				  "{\\\"jobId\\\":null," +
				  "\\\"fileId\\\":\\\"07b17d5d-1010-4980-b280-94129cb13838\\\"}\"}";

		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, null, fileId);
	}

	@Test
	void nullableFileIdOnPassedExecutableTaskDTOOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");

		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, null);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":null}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\"," +
				  "\"errorType\":\"INVALID_TASK_DATA_ERROR\"," +
				  "\"errorMessage\":\"Invalid task request: " +
				  "{\\\"jobId\\\":\\\"780e3129-42a7-4154-8ce9-436fc1a6dc35\\\"," +
				  "\\\"fileId\\\":null}\"}";

		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, null);
	}

	@Test
	void nullableJobProfileOnGottenJobOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		JobDTO jobDTO = createJob(jobId);
		jobDTO.setProfile(null);
		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(jobDTO)
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\"," +
				  "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"," +
				  "\"errorType\":\"INVALID_CONFIGURATION_DATA_ERROR\"," +
				  "\"errorMessage\":\"Missing profile for validation\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void createdJobStatusOnGottenJobOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		JobDTO jobDTO = createJob(jobId);
		jobDTO.setStatus(JobStatus.CREATED);
		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(jobDTO)
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void finishedJobStatusOnGottenJobOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		JobDTO jobDTO = createJob(jobId);
		jobDTO.setStatus(JobStatus.FINISHED);
		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(jobDTO)
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void notFoundJobOnIncreaseTaskProcessingCountTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doThrow(new RestClientResponseException("404 : [no body]", HttpStatus.NOT_FOUND.value(),
		                                                "", null, "no body".getBytes(), null))
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void badRequestExceptionIncreaseTaskProcessingCountTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doThrow(new RestClientResponseException("400 : [incorrect type of parameters]",
		                                                HttpStatus.BAD_REQUEST.value(), "", null,
		                                                "incorrect type of parameters".getBytes(), null))
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void conflictExceptionIncreaseTaskProcessingCountTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doThrow(new RestClientResponseException("409 : [Job with id: " + jobId+ " not in processing state]",
		                                                 HttpStatus.CONFLICT.value(), "", null,
		                                                 ("Job with id: " + jobId+ " not in processing state").getBytes(),
		                                                 null))
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void notFoundJobOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doThrow(new RestClientResponseException("404 : [no body]", HttpStatus.NOT_FOUND.value(),
		                                                "", null, "no body".getBytes(), null))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .rejectAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(queueUtil).rejectAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void badRequestExceptionOnGetJobOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doThrow(new RestClientResponseException("400 : [incorrect type of parameters]",
		                                                HttpStatus.BAD_REQUEST.value(), "", null,
		                                                "incorrect type of parameters".getBytes(), null))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"JOB_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"incorrect type of parameters\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void notFoundFileDescriptorOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doThrow(new RestClientResponseException("404 : [no body]", HttpStatus.NOT_FOUND.value(),
		                                                "", null, "no body".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"no body\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void internalServerExceptionOnGetFileDescriptorOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doThrow(new RestClientResponseException(
				"500 : [internal exception during getting file descriptor]",
				HttpStatus.INTERNAL_SERVER_ERROR.value(), "", null,
				"internal exception during getting file descriptor".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"internal exception during getting file descriptor\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void badRequestExceptionOnGetFileDescriptorOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doThrow(new RestClientResponseException("400 : [incorrect type of parameters]",
		                                                HttpStatus.BAD_REQUEST.value(), "", null,
		                                                "incorrect type of parameters".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"incorrect type of parameters\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void requestTimeoutExceptionOnGetFileDescriptorOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doThrow(new RestClientResponseException("408 : [request timeout]", HttpStatus.REQUEST_TIMEOUT.value(),
		                                                "", null, "request timeout".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"request timeout\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void notFoundResourceOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doThrow(new RestClientResponseException("404 : [no body]", HttpStatus.NOT_FOUND.value(),
		                                                "", null, "no body".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"no body\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void internalServerExceptionOnGetResourceOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doThrow(new RestClientResponseException(
				"500 : [internal exception during getting file resource]",
				HttpStatus.INTERNAL_SERVER_ERROR.value(), "", null,
				"internal exception during getting file resource".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"internal exception during getting file resource\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void badRequestExceptionOnGetResourceOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doThrow(new RestClientResponseException("400 : [incorrect type of parameters]",
		                                                HttpStatus.BAD_REQUEST.value(), "", null,
		                                                "incorrect type of parameters".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"incorrect type of parameters\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void requestTimeoutExceptionOnGetResourceOnProcessTest() {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doThrow(new RestClientResponseException("408 : [incorrect type of parameters]",
		                                                HttpStatus.REQUEST_TIMEOUT.value(), "", null,
		                                                "incorrect type of parameters".getBytes(), null))
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"FILE_OBTAINING_TO_PROCESS_ERROR\","
				  + "\"errorMessage\":\"incorrect type of parameters\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	@Test
	void incorrectChecksumOnSaveFileOnDiskOnProcessTest() throws IOException {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		StoredFileDTO storedFileDTO = createFileDescriptor(fileId);
		storedFileDTO.setContentMD5("Incorrect checksum");
		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(storedFileDTO)
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource())
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"PROCESSING_INTERNAL_ERROR\","
				  + "\"errorMessage\":\"Expected file checksum doesn't match"
				  + " obtained file checksum. Expected: 4043dbacc119258a99820d62552e6a93,"
				  + " actual: Incorrect checksum\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	//@Test
	void internalServerExceptionOnSaveReportFileOnProcessTest() throws IOException {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource())
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		String expectedReportAsString = new String(getClass().getResourceAsStream(
				"/files/report-PDFA_1_A.json").readAllBytes(), StandardCharsets.UTF_8);
		Mockito.doThrow(new RestClientResponseException(
				"500 : [internal exception during saving file report]",
				HttpStatus.INTERNAL_SERVER_ERROR.value(), "", null,
				"internal exception during saving file report".getBytes(), null))
		       .when(localStorageServiceClient)
		       .saveFile(argThat(new FileAsJsonMatcher(expectedReportAsString)),
		                 eq("3dface64e5b27f5fc9b64941eb0b36bf"), eq(MediaType.APPLICATION_JSON));
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"SAVE_RESULT_FILE_ERROR\","
				  + "\"errorMessage\":\"internal exception during saving file report\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(localStorageServiceClient).saveFile(any(File.class),
		                                                   eq("3dface64e5b27f5fc9b64941eb0b36bf"),
		                                                   eq(MediaType.APPLICATION_JSON));
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	//@Test
	void badRequestExceptionOnSaveReportFileOnProcessTest() throws IOException {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource())
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		String expectedReportAsString = new String(getClass().getResourceAsStream(
				"/files/report-PDFA_1_A.json").readAllBytes(), StandardCharsets.UTF_8);
		Mockito.doThrow(new RestClientResponseException("400 : [incorrect parameters]",
		                                                HttpStatus.BAD_REQUEST.value(), "", null,
		                                                "incorrect parameters".getBytes(), null))
		       .when(localStorageServiceClient)
		       .saveFile(argThat(new FileAsJsonMatcher(expectedReportAsString)),
		                 eq("3dface64e5b27f5fc9b64941eb0b36bf"), eq(MediaType.APPLICATION_JSON));
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"SAVE_RESULT_FILE_ERROR\","
				  + "\"errorMessage\":\"incorrect parameters\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(localStorageServiceClient).saveFile(any(File.class),
		                                                   eq("3dface64e5b27f5fc9b64941eb0b36bf"),
		                                                   eq(MediaType.APPLICATION_JSON));
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	//@Test
	void requestTimeoutExceptionOnSaveReportFileOnProcessTest() throws IOException {
		UUID jobId = UUID.fromString("780e3129-42a7-4154-8ce9-436fc1a6dc35");
		UUID fileId = UUID.fromString("07b17d5d-1010-4980-b280-94129cb13838");

		Mockito.doReturn(processingLimit - 1)
		       .when(jobServiceClient)
		       .increaseTaskProcessingCount(jobId, fileId);
		Mockito.doReturn(createJob(jobId))
		       .when(jobServiceClient)
		       .getJobById(jobId);
		Mockito.doReturn(createFileDescriptor(fileId))
		       .when(localStorageServiceClient)
		       .getFileDescriptorById(fileId);
		Mockito.doReturn(createResource())
		       .when(localStorageServiceClient)
		       .getFileResourceById(fileId);
		String expectedReportAsString = new String(getClass().getResourceAsStream(
				"/files/report-PDFA_1_A.json").readAllBytes(), StandardCharsets.UTF_8);
		Mockito.doThrow(new RestClientResponseException("408 : [request timeout]", HttpStatus.REQUEST_TIMEOUT.value(),
		                                                "", null, "request timeout".getBytes(), null))
		       .when(localStorageServiceClient)
		       .saveFile(argThat(new FileAsJsonMatcher(expectedReportAsString)),
		                 eq("f9f83310635b7c01200d2e3d07"), eq(MediaType.APPLICATION_JSON));
		Mockito.doNothing()
		       .when(queueUtil)
		       .applyAndDiscardJob(null, 1, jobId, fileId);

		executableTaskWorker.handleMessage(
				"{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				+ "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\"}", null, 1);

		String expectedMessage
				= "{\"jobId\":\"780e3129-42a7-4154-8ce9-436fc1a6dc35\","
				  + "\"fileId\":\"07b17d5d-1010-4980-b280-94129cb13838\","
				  + "\"errorType\":\"SAVE_RESULT_FILE_ERROR\","
				  + "\"errorMessage\":\"request timeout\"}";

		Mockito.verify(jobServiceClient).increaseTaskProcessingCount(jobId, fileId);
		Mockito.verify(jobServiceClient).getJobById(jobId);
		Mockito.verify(localStorageServiceClient).getFileDescriptorById(fileId);
		Mockito.verify(localStorageServiceClient).getFileResourceById(fileId);
		Mockito.verify(localStorageServiceClient).saveFile(any(File.class),
		                                                   eq("3dface64e5b27f5fc9b64941eb0b36bf"),
		                                                   eq(MediaType.APPLICATION_JSON));
		Mockito.verify(queueSender).sendMessage(expectedMessage);
		Mockito.verify(queueUtil).applyAndDiscardJob(null, 1, jobId, fileId);
	}

	private Resource createResource() throws IOException {
		return createResource("/files/veraPDFTestSuite.pdf");
	}

	private Resource createResource(String resourcePath) throws IOException {
		Path fileToValidatePath
				= Files.createTempFile(tempDir.toPath(), "file_to_validate", "");
		FileUtils.copyToFile(getClass().getResourceAsStream(resourcePath),
		                     fileToValidatePath.toFile());
		return new FileSystemResource(fileToValidatePath);
	}

	private JobDTO createJob(UUID jobId) {
		JobDTO jobDTO = new JobDTO();
		jobDTO.setId(jobId);
		jobDTO.setProfile(Profile.PDFA_1_A);
		jobDTO.setStatus(JobStatus.PROCESSING);
		return jobDTO;
	}

	private StoredFileDTO createFileDescriptor(UUID fileId) {
		return createFileDescriptor(fileId, "veraPDFTestSuite.pdf",
		                            "4043dbacc119258a99820d62552e6a93", 3387);
	}

	private StoredFileDTO createFileDescriptor(UUID fileId, String fileName, String contentMD5, long contentSize) {
		StoredFileDTO storedFileDTO = new StoredFileDTO();
		storedFileDTO.setId(fileId);
		storedFileDTO.setFileName(fileName);
		storedFileDTO.setContentMD5(contentMD5);
		storedFileDTO.setContentType("application/pdf");
		storedFileDTO.setContentSize(contentSize);
		return storedFileDTO;
	}

	private StoredFileDTO createReportFileDescriptor(UUID fileId) {
		StoredFileDTO storedFileDTO = new StoredFileDTO();
		storedFileDTO.setId(UUID.fromString("d39da7b6-3665-4374-84e2-70c1e24e7029"));
		storedFileDTO.setFileName("report-" + fileId);
		storedFileDTO.setContentMD5("35a7d1deffc1ad490bcc5eb3c557a399");
		storedFileDTO.setContentType("application/octet-stream");
		storedFileDTO.setContentSize(617);
		return storedFileDTO;
	}

	private static class FileAsJsonMatcher implements ArgumentMatcher<File> {

		private String expectedReportAsString;

		FileAsJsonMatcher(String expectedReportAsString) {
			this.expectedReportAsString = expectedReportAsString;
		}

		@Override
		public boolean matches(File file) {
			try {
				String actualReportAsString = new String(FileUtils.readFileToByteArray(file),
				                                         StandardCharsets.UTF_8);
				return JSONCompare.compareJSON(expectedReportAsString,
				                               actualReportAsString, JSONCompareMode.NON_EXTENSIBLE).passed();
			} catch (JSONException | IOException e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
