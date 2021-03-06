/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.ocr.tesseract.filter.internal.input;

import java.awt.Image;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.pdfbox.io.IOUtils;
import org.slf4j.Logger;
import org.xwiki.contrib.ocr.api.OCRDocument;
import org.xwiki.contrib.ocr.api.OCRDocumentBuilder;
import org.xwiki.contrib.ocr.api.OCRDocumentBuilderProvider;
import org.xwiki.contrib.ocr.tesseract.filter.OCRInputFilterProperties;
import org.xwiki.contrib.ocr.tesseract.filter.internal.PDFToImageRenderer;
import org.xwiki.contrib.ocr.api.OCRException;
import org.xwiki.contrib.ocr.tesseract.filter.internal.OCRMediaTypeChecker;
import org.xwiki.filter.FilterEventParameters;
import org.xwiki.filter.FilterException;
import org.xwiki.filter.event.model.WikiDocumentFilter;
import org.xwiki.filter.input.AbstractBeanInputFilterStream;
import org.xwiki.filter.input.FileInputSource;
import org.xwiki.filter.input.InputSource;
import org.xwiki.filter.input.InputStreamInputSource;

/**
 * Define generic methods for an OCR input filter stream. Input filter streams derived from this abstract are
 * creating WikiDocument events containing a content specific to the filter.
 *
 * @version $Id$
 * @since 1.0
 */
public abstract class AbstractOCRInputFilterStream
        extends AbstractBeanInputFilterStream<OCRInputFilterProperties, WikiDocumentFilter>
{
    @Inject
    @Named("tesseract")
    private OCRDocumentBuilderProvider builderProvider;

    @Inject
    private Logger logger;

    @Override
    protected void read(Object filter, WikiDocumentFilter proxyFilter) throws FilterException
    {
        OCRDocumentBuilder builder = null;

        try {
            builder = builderProvider.getBuilder(this.properties.getLanguage());

            // TODO: Support multiple streams
            logVerbose("Extracting source ...");
            InputStream source = extractSource(this.properties.getSource());

            logVerbose("Checking source media type ...");
            OCRMediaTypeChecker mediaTypeChecker = new OCRMediaTypeChecker(source);
            logVerbose("Found supported input type : [{}]", mediaTypeChecker.getMediaType());

            // Reset the InputStream so that bytes read by the media type checker can be read again in the rest of
            // the process
            source.close();
            source = extractSource(this.properties.getSource());

            if (mediaTypeChecker.isImage()) {
                logVerbose("Analyzing image ...");
                builder.appendPage(IOUtils.toByteArray(source));
            } else if (mediaTypeChecker.isPDF()) {
                source = extractSource(this.properties.getSource());
                List<Image> images = PDFToImageRenderer.renderPDF(source);

                for (int i = 0; i < images.size(); i++) {
                    logVerbose("Analyzing PDF page {} out of {} ...", i + 1, images.size());
                    builder.appendPage(images.get(i));
                }
            } else {
                throw new FilterException("Unsupported file type.");
            }

            // TODO: Support localized documents
            // Compute the document reference
            String documentName = this.properties.getName();
            logVerbose("Saving document [{}] ...", documentName);
            proxyFilter.beginWikiDocument(documentName, generateEventParameters(builder.getDocument()));
            proxyFilter.endWikiDocument(documentName, FilterEventParameters.EMPTY);

            builder.dispose();
        } catch (IOException e) {
            throw new FilterException("Unable to read source.", e);
        } catch (OCRException e) {
            throw new FilterException("Unable to import the file.", e);
        } finally {
            try {
                if (builder != null) {
                    builder.dispose();
                }
            } catch (OCRException e) {
                throw new FilterException("Unable to dispose the document builder.", e);
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        this.properties.getSource().close();
    }

    /**
     * Check if the given {@link InputSource} is supported and, if so, extract it as a byte array to be used by the
     * parsing library.
     *
     * @param source the {@link InputSource} to use
     * @return the source content
     * @throws FilterException if the {@link InputSource} is not supported
     * @throws IOException if the retrieval of the source content failed
     */
    private InputStream extractSource(InputSource source) throws FilterException, IOException
    {
        if (source instanceof FileInputSource) {
            return new FileInputStream(((FileInputSource) source).getFile());
        }
        if (source instanceof InputStreamInputSource) {
            return ((InputStreamInputSource) source).getInputStream();
        } else {
            throw new FilterException("Unsupported input source.");
        }
    }

    /**
     * Uses the given {@link OCRDocument} to generate {@link FilterEventParameters} that are compatible with
     * {@link WikiDocumentFilter#beginWikiDocument(String, FilterEventParameters)}.
     *
     * @param document the document to use
     * @return the {@link FilterEventParameters}
     * @throws OCRException if an error happens
     */
    protected abstract FilterEventParameters generateEventParameters(OCRDocument document) throws OCRException;

    /**
     * Uses {@link #logger} to log information about the state of the filtering process
     * if {@link OCRInputFilterProperties#isVerbose()} is true.
     *
     * @param message the message to log
     * @param parameters the parameters of the message
     */
    protected void logVerbose(String message, Object... parameters)
    {
        if (this.properties.isVerbose()) {
            this.logger.info(message, parameters);
        }
    }
}
