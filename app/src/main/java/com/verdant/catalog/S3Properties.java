package com.verdant.catalog;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(String endpoint, String bucket, String region, String accessKey, String secretKey) { }
