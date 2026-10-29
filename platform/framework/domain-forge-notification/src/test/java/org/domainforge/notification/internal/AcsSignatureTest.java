package org.domainforge.notification.internal;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 以阿里云《V3 版本请求体＆签名机制》文档中的示例值验证签名实现。
 */
class AcsSignatureTest {
    @Test
    void matchesPublishedExample() {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("RegionId", "cn-shanghai");
        query.put("ImageId", "win2019_1809_x64_dtc_zh-cn_40G_alibase_20230811.vhd");
        var headers = new TreeMap<String, String>();
        headers.put("host", "ecs.cn-shanghai.aliyuncs.com");
        headers.put("x-acs-action", "RunInstances");
        headers.put("x-acs-content-sha256", AcsSignature.EMPTY_SHA256);
        headers.put("x-acs-date", "2023-10-26T10:22:32Z");
        headers.put("x-acs-signature-nonce", "3156853299f313e23d1673dc12e1703d");
        headers.put("x-acs-version", "2014-05-26");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", AcsSignature.EMPTY_SHA256);
        assertEquals("06563a9e1b43f5dfe96b81484da74bceab24a1d853912eee15083a6f0f3283c0",
                AcsSignature.sign("POST", query, headers, "", "YourAccessKeySecret"));
        assertEquals("host;x-acs-action;x-acs-content-sha256;x-acs-date;x-acs-signature-nonce;x-acs-version",
                AcsSignature.signedHeaders(headers));
    }

    @Test
    void encodesPerRfc3986() {
        assertEquals("a%20b%2A~-_.%2B%2F", AcsSignature.encode("a b*~-_.+/"));
        assertEquals("%E9%AA%8C%E8%AF%81", AcsSignature.encode("验证"));
        assertEquals("%7B%22code%22%3A%221%22%7D", AcsSignature.encode("{\"code\":\"1\"}"));
    }
}
