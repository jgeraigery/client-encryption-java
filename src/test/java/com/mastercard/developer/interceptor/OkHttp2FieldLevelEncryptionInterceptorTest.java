package com.mastercard.developer.interceptor;

import com.mastercard.developer.encryption.EncryptionException;
import com.mastercard.developer.encryption.FieldLevelEncryptionConfig;
import com.mastercard.developer.interceptors.OkHttp2FieldLevelEncryptionInterceptor;
import com.mastercard.developer.test.TestUtils;
import com.squareup.okhttp.*;
import okio.Buffer;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static com.mastercard.developer.test.TestUtils.assertPayloadEquals;
import static com.mastercard.developer.test.TestUtils.getTestFieldLevelEncryptionConfigBuilder;
import static com.squareup.okhttp.Interceptor.Chain;
import static org.hamcrest.core.Is.isA;
import static org.mockito.Mockito.*;

public class OkHttp2FieldLevelEncryptionInterceptorTest {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Test
    public void testIntercept_ShouldEncryptRequestPayloadAndUpdateContentLengthHeader() throws Exception {

        // GIVEN
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder()
                .withEncryptionPath("$.foo", "$.encryptedFoo")
                .build();
        Request request = new Request.Builder()
                .url("https://sandbox.api.mastercard.com/service")
                .post(RequestBody.create(JSON_MEDIA_TYPE, "{\"foo\":\"bar\"}"))
                .build();
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        when(chain.request()).thenReturn(request);
        when(response.body()).thenReturn(null);
        when(chain.proceed(any(Request.class))).thenReturn(response);

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        instanceUnderTest.intercept(chain);

        // THEN
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(chain).proceed(requestCaptor.capture());
        Request encryptedRequest = requestCaptor.getAllValues().get(0);
        Buffer buffer = new Buffer();
        encryptedRequest.body().writeTo(buffer);
        String encryptedPayload = buffer.readUtf8();
        Assert.assertFalse(encryptedPayload.contains("foo"));
        Assert.assertTrue(encryptedPayload.contains("encryptedFoo"));
        Assert.assertEquals(encryptedPayload.length(), encryptedRequest.body().contentLength());
    }

    @Test
    public void testIntercept_ShouldDoNothing_WhenRequestWithoutPayload() throws Exception {

        // GIVEN
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder()
                .withEncryptionPath("$.foo", "$.encryptedFoo")
                .build();
        Request request = mock(Request.class);
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        when(request.body()).thenReturn(null);
        when(response.body()).thenReturn(null);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(response);

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        instanceUnderTest.intercept(chain);

        // THEN
        verify(request).body();
        verifyNoMoreInteractions(request);
    }

    @Test
    public void testIntercept_ShouldThrowIOException_WhenEncryptionFails() throws Exception {

        // GIVEN
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder()
                .withEncryptionPath("$.foo", "$.encryptedFoo")
                .withEncryptionCertificate(TestUtils.getTestInvalidEncryptionCertificate()) // Invalid certificate
                .build();
        Request request = new Request.Builder()
                .url("https://sandbox.api.mastercard.com/service")
                .post(RequestBody.create(JSON_MEDIA_TYPE, "{\"foo\":\"bar\"}"))
                .build();
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        when(chain.request()).thenReturn(request);
        when(response.body()).thenReturn(null);
        when(chain.proceed(any(Request.class))).thenReturn(response);

        // THEN
        expectedException.expect(IOException.class);
        expectedException.expectMessage("Failed to encrypt request!");
        expectedException.expectCause(isA(EncryptionException.class));

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        instanceUnderTest.intercept(chain);
    }

    @Test
    public void testIntercept_ShouldDecryptResponsePayloadAndUpdateContentLengthHeader() throws Exception {

        // GIVEN
        String encryptedPayload = "{" +
                "    \"encryptedData\": {" +
                "        \"iv\": \"a32059c51607d0d02e823faecda5fb15\"," +
                "        \"encryptedKey\": \"a31cfe7a7981b72428c013270619554c1d645c04b9d51c7eaf996f55749ef62fd7c7f8d334f95913be41ae38c46d192670fd1acb84ebb85a00cd997f1a9a3f782229c7bf5f0fdf49fe404452d7ed4fd41fbb95b787d25893fbf3d2c75673cecc8799bbe3dd7eb4fe6d3f744b377572cdf8aba1617194e10475b6cd6a8dd4fb8264f8f51534d8f7ac7c10b4ce9c44d15066724b03a0ab0edd512f9e6521fdb5841cd6964e457d6b4a0e45ba4aac4e77d6bbe383d6147e751fa88bc26278bb9690f9ee84b17123b887be2dcef0873f4f9f2c895d90e23456fafb01b99885e31f01a3188f0ad47edf22999cc1d0ddaf49e1407375117b5d66f1f185f2b57078d255\"," +
                "        \"encryptedValue\": \"21d754bdb4567d35d58720c9f8364075\"," +
                "        \"oaepHashingAlgorithm\": \"SHA256\"" +
                "    }" +
                "}";
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder()
                .withDecryptionPath("$.encryptedData", "$.data")
                .build();
        Request request = mock(Request.class);
        Response encryptedResponse = new Response.Builder()
                .body(ResponseBody.create(JSON_MEDIA_TYPE, encryptedPayload))
                .request(request)
                .code(200)
                .protocol(Protocol.HTTP_1_1)
                .build();
        Chain chain = mock(Chain.class);
        when(request.body()).thenReturn(null);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(encryptedResponse);

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        Response response = instanceUnderTest.intercept(chain);

        // THEN
        String payload = response.body().string();
        assertPayloadEquals("{\"data\":\"string\"}", payload);
        Assert.assertEquals(payload.length(), response.body().contentLength());
    }

    @Test
    public void testIntercept_ShouldDoNothing_WhenResponseWithoutPayload() throws Exception {

        // GIVEN
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder().build();
        Request request = mock(Request.class);
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(response);
        when(response.body()).thenReturn(null);

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        instanceUnderTest.intercept(chain);

        // THEN
        verify(response).body();
        verifyNoMoreInteractions(response);
    }

    @Test
    public void testIntercept_ShouldDoNothing_WhenResponseWithEmptyPayload() throws Exception {

        // GIVEN
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder().build();
        Request request = mock(Request.class);
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(response);
        when(response.body()).thenReturn(ResponseBody.create(JSON_MEDIA_TYPE, ""));

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        instanceUnderTest.intercept(chain);

        // THEN
        verify(response).body();
        verifyNoMoreInteractions(response);
    }

    @Test
    public void testIntercept_ShouldThrowIOException_WhenDecryptionFails() throws Exception {

        // GIVEN
        String encryptedPayload = "{" +
                "    \"encryptedData\": {" +
                "        \"iv\": \"a2c494ca28dec4f3d6ce7d68b1044cfe\"," +
                "        \"encryptedKey\": \"NOT A VALID KEY!\"," +
                "        \"encryptedValue\": \"0672589113046bf692265b6ea6088184\"" +
                "    }" +
                "}";
        FieldLevelEncryptionConfig config = getTestFieldLevelEncryptionConfigBuilder()
                .withDecryptionPath("$.encryptedData", "$.data")
                .build();
        Request request = mock(Request.class);
        Chain chain = mock(Chain.class);
        Response response = mock(Response.class);
        when(request.body()).thenReturn(null);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any(Request.class))).thenReturn(response);
        when(response.body()).thenReturn(ResponseBody.create(JSON_MEDIA_TYPE, encryptedPayload));

        // THEN
        expectedException.expect(IOException.class);
        expectedException.expectMessage("Failed to decrypt response!");
        expectedException.expectCause(isA(EncryptionException.class));

        // WHEN
        OkHttp2FieldLevelEncryptionInterceptor instanceUnderTest = new OkHttp2FieldLevelEncryptionInterceptor(config);
        instanceUnderTest.intercept(chain);
    }
}
