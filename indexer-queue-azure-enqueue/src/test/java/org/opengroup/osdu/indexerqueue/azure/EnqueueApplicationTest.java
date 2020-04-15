// Copyright © Microsoft Corporation
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//      http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.opengroup.osdu.indexerqueue.azure;

import com.microsoft.azure.functions.ExecutionContext;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.junit.*;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.opengroup.osdu.core.common.model.http.AppException;
import org.opengroup.osdu.core.common.model.search.DeploymentEnvironment;
import org.opengroup.osdu.core.common.search.Config;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import java.io.BufferedReader;
import java.util.Date;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.Assert.fail;
import static org.mockito.MockitoAnnotations.initMocks;
import static org.powermock.api.mockito.PowerMockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ Config.class, HttpClientBuilder.class })
@PowerMockIgnore("javax.net.ssl.*")
public class EnqueueApplicationTest {

  private ExecutionContext executionContext;
  private final String requestBodyEmpty = "{}";
  private final String requestBodyValid = "{\"message\":{\"data\":[{\"id\":\"common:welldb:raj21\",\"kind\":\"common:welldb:wellbore:1.0.0\",\"op\":\"create\"}],\"account-id\":\"common\",\"data-partition-id\":\"common\",\"correlation-id\":\"ee85038e-4510-49d9-b2ec-3651315a4d00\"}}";
  private final String requestBodyMissingCorrelationId = "{\"message\":{\"data\":[{\"id\":\"common:welldb:raj21\",\"kind\":\"common:welldb:wellbore:1.0.0\",\"op\":\"create\"}],\"account-id\":\"common\",\"data-partition-id\":\"common\"}}";
  private final String requestBodyMissingTenantIdAndAccountId = "{\"message\":{\"data\":[{\"id\":\"common:welldb:raj21\",\"kind\":\"common:welldb:wellbore:1.0.0\",\"op\":\"create\"}],\"correlation-id\":\"ee85038e-4510-49d9-b2ec-3651315a4d00\"}}";

  private final String ACCOUNT_ID = "any-account";

  @Mock
  private HttpServletRequest request;
  @Mock
  private BufferedReader bufferReader;
  @Mock
  private HttpPost httpPost;
  @Mock
  private CloseableHttpResponse httpResponse;
  @Mock
  private CloseableHttpClient httpClient;
  @Mock
  private HttpClientBuilder httpClientBuilder;

  @InjectMocks
  private EnqueueFunction sut;

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

  @Before
  public void setup() throws Exception {
    initMocks(this);

    mockStatic(Config.class);
    environmentVariables.set("INDEXER_WORKER_URL", "index-worker-url");
    executionContext = mock(ExecutionContext.class);

    when(request.getReader()).thenReturn(bufferReader);
    when(Config.getDeploymentEnvironment()).thenReturn(DeploymentEnvironment.LOCAL);
    when(executionContext.getLogger()).thenReturn(Logger.getGlobal());
    when(httpClientBuilder.build()).thenReturn(httpClient);
    when(httpClient.execute(httpPost)).thenReturn(httpResponse);
  }

  @Test
  public void should_return200_when_given_validRequest_enqueueTaskTest() {
    should_return200_enqueueTaskTest(requestBodyValid);
  }

  @Test
  public void should_return200AndSevereLog_when_missing_correlationId_enqueueTaskTest() {
    should_return200_enqueueTaskTest(requestBodyMissingCorrelationId);
  }

  @Test
  public void should_return400_when_missing_tenantIdAndAccountIdInRequestBody_enqueueTaskTest() {
    should_return400_enqueueTaskTest(requestBodyMissingTenantIdAndAccountId, "tenant-id missing");
  }

  @Test
  public void should_return400_when_given_emptyRequestBody_enqueueTaskTest() {
    should_return400_enqueueTaskTest(requestBodyEmpty, "message object not found");
  }

  private void should_return400_enqueueTaskTest(String requestBody, String errorMessage) {
    try {
      createRequestStream(requestBody);
      this.sut.run(requestBody, "1234", new Date().toString(), "1", executionContext);
      fail("Should throw exception");
    } catch (AppException e) {
      Assert.assertEquals(HttpStatus.SC_BAD_REQUEST, e.getError().getCode());
      Assert.assertEquals(errorMessage, e.getError().getMessage());
    } catch (Exception e) {
      fail("Should not throw this exception" + e.getMessage());
    }
  }

  private void should_return200_enqueueTaskTest(String requestBody) {
    createRequestStream(requestBody);
    Response response = null;
    try {
      response = this.sut.run(requestBody, "4321", new Date().toString(), "1", executionContext);
    } catch (Exception e) {
      e.printStackTrace();
    }
    Assert.assertEquals(HttpStatus.SC_OK, response.getStatus());
  }

  private void createRequestStream(String requestBody) {
    String[] requestBodies = { requestBody };
    Stream<String> stringStream = Stream.of(requestBodies);
    when(bufferReader.lines()).thenReturn(stringStream);
  }
}

