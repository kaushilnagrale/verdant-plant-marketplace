package com.verdant.catalog;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

@Service
public class ListingImageService {

    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final S3Client s3;
    private final S3Properties props;
    private final ListingImageRepository images;

    public ListingImageService(S3Client s3, S3Properties props, ListingImageRepository images) {
        this.s3 = s3;
        this.props = props;
        this.images = images;
    }

    public void upload(UUID listingId, MultipartFile file) {
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Image exceeds 10 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read upload");
        }
        if (!isAllowedImage(bytes)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Only JPEG, PNG, or WebP images are accepted");
        }

        int position = images.countByListingId(listingId);
        uploadSize(listingId, bytes, "thumb", 300, position);
        uploadSize(listingId, bytes, "medium", 800, position);
        uploadSize(listingId, bytes, "full", 1600, position);
    }

    private void uploadSize(UUID listingId, byte[] original, String label, int maxDimension, int position) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thumbnails.of(new ByteArrayInputStream(original))
                .size(maxDimension, maxDimension)
                .outputFormat("jpg")
                .toOutputStream(out);
            byte[] resized = out.toByteArray();

            String key = "listings/%s/%s-%s.jpg".formatted(listingId, label, UUID.randomUUID());
            s3.putObject(PutObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .contentType("image/jpeg")
                    .build(),
                RequestBody.fromBytes(resized));

            images.save(new ListingImage(listingId, key, position));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process image");
        }
    }

    /** Detects file type by magic bytes, not extension, since extensions are trivially forged. */
    private boolean isAllowedImage(byte[] bytes) {
        if (bytes.length < 12) return false;
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return true; // JPEG
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) return true; // PNG
        return bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P'; // WebP
    }
}
