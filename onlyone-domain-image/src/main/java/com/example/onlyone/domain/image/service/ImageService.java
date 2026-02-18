package com.example.onlyone.domain.image.service;

import com.example.onlyone.domain.image.dto.request.PresignedUrlRequestDto;
import com.example.onlyone.domain.image.dto.response.PresignedUrlResponseDto;
import com.example.onlyone.domain.image.entity.ImageFolderType;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.cloudfront.domain}")
    private String cloudfrontDomain;

    private static final long MAX_IMAGE_SIZE = 5 * 1024 * 1024; // 5MB
    private static final int PRESIGN_EXPIRY_MINUTES = 10;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

    public PresignedUrlResponseDto generatePresignedUrl(String folderType, PresignedUrlRequestDto request) {
        ImageFolderType folder = ImageFolderType.from(folderType);
        validateContentType(request.contentType());
        validateImageSize(request.imageSize());

        String fileName = generateFileName(request.fileName());
        String key = folder.getFolder() + "/" + fileName;

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(request.contentType())
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(PRESIGN_EXPIRY_MINUTES))
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = s3Presigner.presignPutObject(presignRequest);
        log.info("Generated presigned URL for file: {}", key);

        String presignedUrl = presignedRequest.url().toString();
        String imageUrl = buildImageUrl(folder, fileName);

        return new PresignedUrlResponseDto(presignedUrl, imageUrl);
    }

    private String buildImageUrl(ImageFolderType folder, String fileName) {
        return String.format("https://%s/%s/%s",
                cloudfrontDomain, folder.getFolder(), fileName);
    }

    private String generateFileName(String originalFileName) {
        String extension = getFileExtension(originalFileName);
        return UUID.randomUUID().toString() + extension;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    private void validateContentType(String contentType) {
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_CONTENT_TYPE);
        }
    }

    private void validateImageSize(Long imageSize) {
        if (imageSize == null || imageSize <= 0) {
            throw new CustomException(ErrorCode.INVALID_IMAGE_SIZE);
        }
        if (imageSize > MAX_IMAGE_SIZE) {
            throw new CustomException(ErrorCode.IMAGE_SIZE_EXCEEDED);
        }
    }
}
