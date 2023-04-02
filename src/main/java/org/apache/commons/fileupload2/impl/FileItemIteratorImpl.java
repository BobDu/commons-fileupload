/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.fileupload2.impl;

import static java.lang.String.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.apache.commons.fileupload2.FileItem;
import org.apache.commons.fileupload2.FileItemHeaders;
import org.apache.commons.fileupload2.FileItemIterator;
import org.apache.commons.fileupload2.FileItemStream;
import org.apache.commons.fileupload2.FileUploadBase;
import org.apache.commons.fileupload2.FileUploadException;
import org.apache.commons.fileupload2.MultipartStream;
import org.apache.commons.fileupload2.ProgressListener;
import org.apache.commons.fileupload2.RequestContext;
import org.apache.commons.fileupload2.UploadContext;
import org.apache.commons.fileupload2.pub.FileUploadSizeException;
import org.apache.commons.fileupload2.pub.FileUploadContentTypeException;
import org.apache.commons.fileupload2.util.LimitedInputStream;
import org.apache.commons.io.IOUtils;

/**
 * The iterator, which is returned by {@link FileUploadBase#getItemIterator(RequestContext)}.
 */
public class FileItemIteratorImpl implements FileItemIterator {
    /**
     * The file uploads processing utility.
     *
     * @see FileUploadBase
     */
    private final FileUploadBase fileUploadBase;
    /**
     * The request context.
     *
     * @see RequestContext
     */
    private final RequestContext ctx;
    /**
     * The maximum allowed size of a complete request.
     */
    private long sizeMax;
    /**
     * The maximum allowed size of a single uploaded file.
     */
    private long fileSizeMax;

    /**
     * The multi part stream to process.
     */
    private MultipartStream multiPartStream;

    /**
     * The notifier, which used for triggering the {@link ProgressListener}.
     */
    private MultipartStream.ProgressNotifier progressNotifier;

    /**
     * The boundary, which separates the various parts.
     */
    private byte[] multiPartBoundary;

    /**
     * The item, which we currently process.
     */
    private FileItemStreamImpl currentItem;

    /**
     * The current items field name.
     */
    private String currentFieldName;

    /**
     * Whether we are currently skipping the preamble.
     */
    private boolean skipPreamble;

    /**
     * Whether the current item may still be read.
     */
    private boolean itemValid;

    /**
     * Whether we have seen the end of the file.
     */
    private boolean eof;

    /**
     * Creates a new instance.
     *
     * @param fileUploadBase Main processor.
     * @param requestContext The request context.
     * @throws FileUploadException An error occurred while parsing the request.
     * @throws IOException         An I/O error occurred.
     */
    public FileItemIteratorImpl(final FileUploadBase fileUploadBase, final RequestContext requestContext) throws FileUploadException, IOException {
        this.fileUploadBase = fileUploadBase;
        this.sizeMax = fileUploadBase.getSizeMax();
        this.fileSizeMax = fileUploadBase.getFileSizeMax();
        this.ctx = Objects.requireNonNull(requestContext, "requestContext");
        this.skipPreamble = true;
        findNextItem();
    }

    /**
     * Called for finding the next item, if any.
     *
     * @return True, if an next item was found, otherwise false.
     * @throws IOException An I/O error occurred.
     */
    private boolean findNextItem() throws FileUploadException, IOException {
        if (eof) {
            return false;
        }
        if (currentItem != null) {
            currentItem.close();
            currentItem = null;
        }
        final MultipartStream multi = getMultiPartStream();
        for (;;) {
            final boolean nextPart;
            if (skipPreamble) {
                nextPart = multi.skipPreamble();
            } else {
                nextPart = multi.readBoundary();
            }
            if (!nextPart) {
                if (currentFieldName == null) {
                    // Outer multipart terminated -> No more data
                    eof = true;
                    return false;
                }
                // Inner multipart terminated -> Return to parsing the outer
                multi.setBoundary(multiPartBoundary);
                currentFieldName = null;
                continue;
            }
            final FileItemHeaders headers = fileUploadBase.getParsedHeaders(multi.readHeaders());
            if (currentFieldName == null) {
                // We're parsing the outer multipart
                final String fieldName = fileUploadBase.getFieldName(headers);
                if (fieldName != null) {
                    final String subContentType = headers.getHeader(FileUploadBase.CONTENT_TYPE);
                    if (subContentType != null && subContentType.toLowerCase(Locale.ENGLISH).startsWith(FileUploadBase.MULTIPART_MIXED)) {
                        currentFieldName = fieldName;
                        // Multiple files associated with this field name
                        final byte[] subBoundary = fileUploadBase.getBoundary(subContentType);
                        multi.setBoundary(subBoundary);
                        skipPreamble = true;
                        continue;
                    }
                    final String fileName = fileUploadBase.getFileName(headers);
                    currentItem = new FileItemStreamImpl(this, fileName, fieldName, headers.getHeader(FileUploadBase.CONTENT_TYPE), fileName == null,
                            getContentLength(headers));
                    currentItem.setHeaders(headers);
                    progressNotifier.noteItem();
                    itemValid = true;
                    return true;
                }
            } else {
                final String fileName = fileUploadBase.getFileName(headers);
                if (fileName != null) {
                    currentItem = new FileItemStreamImpl(this, fileName, currentFieldName, headers.getHeader(FileUploadBase.CONTENT_TYPE), false,
                            getContentLength(headers));
                    currentItem.setHeaders(headers);
                    progressNotifier.noteItem();
                    itemValid = true;
                    return true;
                }
            }
            multi.discardBodyData();
        }
    }

    private long getContentLength(final FileItemHeaders pHeaders) {
        try {
            return Long.parseLong(pHeaders.getHeader(FileUploadBase.CONTENT_LENGTH));
        } catch (final Exception e) {
            return -1;
        }
    }

    @Override
    public List<FileItem> getFileItems() throws FileUploadException, IOException {
        final List<FileItem> items = new ArrayList<>();
        while (hasNext()) {
            final FileItemStream fis = next();
            final FileItem fi = fileUploadBase.getFileItemFactory().createItem(fis.getFieldName(), fis.getContentType(), fis.isFormField(), fis.getName());
            items.add(fi);
        }
        return items;
    }

    @Override
    public long getFileSizeMax() {
        return fileSizeMax;
    }

    public MultipartStream getMultiPartStream() throws FileUploadException, IOException {
        if (multiPartStream == null) {
            init(fileUploadBase, ctx);
        }
        return multiPartStream;
    }

    @Override
    public long getSizeMax() {
        return sizeMax;
    }

    /**
     * Returns, whether another instance of {@link FileItemStream} is available.
     *
     * @throws FileUploadException Parsing or processing the file item failed.
     * @throws IOException         Reading the file item failed.
     * @return True, if one or more additional file items are available, otherwise false.
     */
    @Override
    public boolean hasNext() throws FileUploadException, IOException {
        if (eof) {
            return false;
        }
        if (itemValid) {
            return true;
        }
        return findNextItem();
    }

    protected void init(final FileUploadBase fileUploadBase, final RequestContext pRequestContext) throws FileUploadException, IOException {
        final String contentType = ctx.getContentType();
        if ((null == contentType) || (!contentType.toLowerCase(Locale.ENGLISH).startsWith(FileUploadBase.MULTIPART))) {
            throw new FileUploadContentTypeException(format("the request doesn't contain a %s or %s stream, content type header is %s",
                    FileUploadBase.MULTIPART_FORM_DATA, FileUploadBase.MULTIPART_MIXED, contentType), contentType);
        }
        final long contentLengthInt = ((UploadContext) ctx).contentLength();
        // @formatter:off
        final long requestSize = UploadContext.class.isAssignableFrom(ctx.getClass())
                                 // Inline conditional is OK here CHECKSTYLE:OFF
                                 ? ((UploadContext) ctx).contentLength()
                                 : contentLengthInt;
                                 // CHECKSTYLE:ON
        // @formatter:on
        final InputStream input; // N.B. this is eventually closed in MultipartStream processing
        if (sizeMax >= 0) {
            if (requestSize != -1 && requestSize > sizeMax) {
                throw new FileUploadSizeException(
                        format("the request was rejected because its size (%s) exceeds the configured maximum (%s)", requestSize, sizeMax), sizeMax,
                        requestSize);
            }
            // N.B. this is eventually closed in MultipartStream processing
            input = new LimitedInputStream(ctx.getInputStream(), sizeMax) {
                @Override
                protected void raiseError(final long pSizeMax, final long pCount) throws IOException {
                    throw new FileUploadSizeException(
                            format("The request was rejected because its size (%s) exceeds the configured maximum (%s)", pCount, pSizeMax), pSizeMax, pCount);
                }
            };
        } else {
            input = ctx.getInputStream();
        }

        String charEncoding = fileUploadBase.getHeaderEncoding();
        if (charEncoding == null) {
            charEncoding = ctx.getCharacterEncoding();
        }

        multiPartBoundary = fileUploadBase.getBoundary(contentType);
        if (multiPartBoundary == null) {
            IOUtils.closeQuietly(input); // avoid possible resource leak
            throw new FileUploadException("the request was rejected because no multipart boundary was found");
        }

        progressNotifier = new MultipartStream.ProgressNotifier(fileUploadBase.getProgressListener(), requestSize);
        try {
            multiPartStream = new MultipartStream(input, multiPartBoundary, progressNotifier);
        } catch (final IllegalArgumentException e) {
            IOUtils.closeQuietly(input); // avoid possible resource leak
            throw new FileUploadContentTypeException(format("The boundary specified in the %s header is too long", FileUploadBase.CONTENT_TYPE), e);
        }
        multiPartStream.setHeaderEncoding(charEncoding);
    }

    /**
     * Returns the next available {@link FileItemStream}.
     *
     * @throws java.util.NoSuchElementException No more items are available. Use {@link #hasNext()} to prevent this exception.
     * @throws FileUploadException              Parsing or processing the file item failed.
     * @throws IOException                      Reading the file item failed.
     * @return FileItemStream instance, which provides access to the next file item.
     */
    @Override
    public FileItemStream next() throws FileUploadException, IOException {
        if (eof || (!itemValid && !hasNext())) {
            throw new NoSuchElementException();
        }
        itemValid = false;
        return currentItem;
    }

    @Override
    public void setFileSizeMax(final long fileSizeMax) {
        this.fileSizeMax = fileSizeMax;
    }

    @Override
    public void setSizeMax(final long sizeMax) {
        this.sizeMax = sizeMax;
    }

}