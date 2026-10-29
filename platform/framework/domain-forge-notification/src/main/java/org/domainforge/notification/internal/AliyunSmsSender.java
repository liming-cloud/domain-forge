package org.domainforge.notification.internal;

import io.micrometer.core.instrument.MeterRegistry;
import org.domainforge.notification.api.NotificationUnavailableException;
import org.domainforge.notification.api.SmsMessage;
import org.domainforge.notification.api.SmsReceipt;
import org.domainforge.notification.api.SmsRejected;
import org.domainforge.notification.api.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信 SendSms（API 版本 2017-05-25，V3 签名，直接调用 HTTP 接口）。同步发送、不重试；
 * 失败分类：号码无效与频控为业务拒绝，其余（网络、超时、5xx、账号／签名／模板／余额问题）为不可用。
 */
public final class AliyunSmsSender implements SmsSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(AliyunSmsSender.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> INVALID_PHONE = Set.of("isv.MOBILE_NUMBER_ILLEGAL", "isv.MOBILE_COUNT_OVER_LIMIT");
    private static final Set<String> RATE_LIMITED = Set.of("isv.BUSINESS_LIMIT_CONTROL", "isv.DAY_LIMIT_CONTROL");
    private final Settings settings;
    private final HttpClient http;
    private final MeterRegistry metrics;
    private final Clock clock;

    AliyunSmsSender(Settings settings, MeterRegistry metrics, Clock clock) {
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public SmsReceipt send(SmsMessage message) {
        String phone = PhoneNumbers.normalize(message.phone());
        String templateCode = settings.templates().get(message.template());
        if (templateCode == null) throw new IllegalArgumentException("未配置短信模板：" + message.template());
        try {
            SmsReceipt receipt = classify(call(phone, templateCode, message.parameters()));
            metrics.counter("domainforge.sms.sent", "template", message.template(), "result", "success").increment();
            return receipt;
        } catch (SmsRejected rejected) {
            metrics.counter("domainforge.sms.sent", "template", message.template(), "result", "rejected").increment();
            LOGGER.warn("sms_rejected phone={} template={} reason={}", PhoneNumbers.mask(phone), message.template(),
                    rejected.reason());
            throw rejected;
        } catch (NotificationUnavailableException failure) {
            metrics.counter("domainforge.sms.sent", "template", message.template(), "result", "failure").increment();
            LOGGER.error("sms_failed phone={} template={}", PhoneNumbers.mask(phone), message.template(), failure);
            throw failure;
        }
    }

    private HttpResponse<String> call(String phone, String templateCode, Map<String, String> parameters) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("PhoneNumbers", phone);
        query.put("SignName", settings.signName());
        query.put("TemplateCode", templateCode);
        if (!parameters.isEmpty()) query.put("TemplateParam", JSON.writeValueAsString(new TreeMap<>(parameters)));
        URI endpoint = settings.endpoint();
        var headers = new TreeMap<String, String>();
        headers.put("host", endpoint.getAuthority());
        headers.put("x-acs-action", "SendSms");
        headers.put("x-acs-content-sha256", AcsSignature.EMPTY_SHA256);
        headers.put("x-acs-date",
                DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        headers.put("x-acs-signature-nonce", UUID.randomUUID().toString().replace("-", ""));
        headers.put("x-acs-version", "2017-05-25");
        String signature = AcsSignature.sign("POST", query, headers, "", settings.accessKeySecret());
        var request = HttpRequest.newBuilder(endpoint.resolve("/?" + AcsSignature.canonicalQuery(query)))
                .timeout(settings.requestTimeout())
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Authorization", AcsSignature.ALGORITHM + " Credential=" + settings.accessKeyId()
                        + ",SignedHeaders=" + AcsSignature.signedHeaders(headers) + ",Signature=" + signature);
        headers.forEach((name, value) -> {
            if (!name.equals("host")) request.header(name, value);
        });
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException failure) {
            throw new NotificationUnavailableException("短信服务商不可达或超时", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NotificationUnavailableException("短信发送被中断", interrupted);
        }
    }

    private static SmsReceipt classify(HttpResponse<String> response) {
        JsonNode body;
        try {
            body = JSON.readTree(response.body());
        } catch (JacksonException unreadable) {
            throw new NotificationUnavailableException("短信服务商响应无法解析：HTTP " + response.statusCode(), unreadable);
        }
        String code = body.path("Code").asString("");
        if (response.statusCode() == 200 && code.equals("OK")) return new SmsReceipt(body.path("BizId").asString(""));
        if (INVALID_PHONE.contains(code)) throw new SmsRejected(SmsRejected.Reason.INVALID_PHONE, "手机号无效");
        if (RATE_LIMITED.contains(code)) throw new SmsRejected(SmsRejected.Reason.RATE_LIMITED, "发送过于频繁，请稍后再试");
        throw new NotificationUnavailableException("短信服务商拒绝：HTTP " + response.statusCode() + " " + code, null);
    }

    /**
     * 阿里云短信设置。
     *
     * @param endpoint        接入地址（默认 https://dysmsapi.aliyuncs.com）
     * @param accessKeyId     AccessKey ID
     * @param accessKeySecret AccessKey Secret
     * @param signName        短信签名
     * @param templates       逻辑模板名到服务商模板编号
     * @param connectTimeout  连接超时
     * @param requestTimeout  请求超时
     */
    record Settings(URI endpoint, String accessKeyId, String accessKeySecret, String signName,
                    Map<String, String> templates, Duration connectTimeout, Duration requestTimeout) {
        @Override
        public String toString() {
            return "Settings[endpoint=" + endpoint + ", signName=" + signName + ", templates=" + templates + "]";
        }
    }
}
