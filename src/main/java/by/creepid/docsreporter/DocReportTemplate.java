package by.creepid.docsreporter;

import by.creepid.docsreporter.context.ContextFactory;
import by.creepid.docsreporter.context.DocContextProcessor;
import by.creepid.docsreporter.context.DocReportFactory;
import by.creepid.docsreporter.context.meta.MetadataFillerChain;
import by.creepid.docsreporter.context.validation.ReportFieldsValidator;
import by.creepid.docsreporter.context.validation.ReportProcessingException;
import by.creepid.docsreporter.converter.DocConverterAdapter;
import by.creepid.docsreporter.converter.DocFormat;
import static by.creepid.docsreporter.converter.DocFormat.*;
import by.creepid.docsreporter.converter.images.ImageExtractObserver;
import fr.opensagres.xdocreport.core.XDocReportException;
import java.io.OutputStream;
import org.springframework.stereotype.Service;
import fr.opensagres.xdocreport.document.IXDocReport;
import fr.opensagres.xdocreport.template.FieldsExtractor;
import fr.opensagres.xdocreport.template.IContext;
import fr.opensagres.xdocreport.template.formatter.FieldsMetadata;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ReflectionUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.ValidationUtils;
import org.springframework.validation.Validator;

@Service
public class DocReportTemplate implements ReportTemplate, ResourceLoaderAware {

    @Value("${driver.sax}")
    private String saxDriver;

    @Autowired
    private DocReportFactory docReportFactory;
    @Autowired
    private ContextFactory contextFactory;

    @Resource(name = "docConverters")
    private List<DocConverterAdapter> docConverters;

    @Autowired(required = false)
    private MetadataFillerChain metadataFillerChain;

    private ThreadLocal<IContext> contextLocal;
    private IXDocReport docReport;

    private Class<?> modelClass;
    private String modelName;

    private String templatePath;
    private org.springframework.core.io.Resource templateResource;

    private DocFormat templateFormat;

    private FieldsMetadata metadata;
    private Map<String, Class<?>> iteratorNames;

    private Validator reportValidator;

    private volatile Errors fieldErrors;

    private ResourceLoader resourceLoader;

    public DocReportTemplate() {
        metadata = new FieldsMetadata();
    }

    public void initContext() {
        templateResource = resourceLoader.getResource(templatePath);

        templateFormat = getFormat(templateResource.getFilename());

        if (templateFormat == UNSUPPORTED) {
            throw new IllegalStateException("Given template format is not supported!");
        }

        if (modelClass == null) {
            throw new IllegalStateException("Model class must be set!");
        }

        docReport = docReportFactory.buildReport(templateResource);
        contextLocal = new ThreadLocal<IContext>();

        if (metadataFillerChain != null) {
            metadataFillerChain.fillMetadata(metadata, modelClass, modelName, iteratorNames);
        }
        docReport.setFieldsMetadata(metadata);

        clearSAXDriverProperty();

        reportValidator = new ReportFieldsValidator(modelClass, modelName, iteratorNames);
    }

    private IContext getContext() {
        if (contextLocal.get() == null) {
            contextLocal.set(
                    contextFactory.buildContext(docReport));
        }

        return contextLocal.get();
    }

    private void clearSAXDriverProperty() {
        if (System.getProperty(saxDriver) != null) {
            System.clearProperty(saxDriver);
        }
    }

    private DocConverterAdapter findConverter(DocFormat format) {
        if (docConverters == null) {
            throw new IllegalStateException("No document converters found!");
        }

        for (DocConverterAdapter docConverter : docConverters) {
            if (docConverter != null
                    && docConverter.getTargetFormat() == format
                    && docConverter.getSourceFormat() == templateFormat) {
                return docConverter;
            }
        }

        throw new IllegalStateException("No document converters found!");
    }

    private synchronized void validate(Object model)
            throws ReportProcessingException {
        try {
            FieldsExtractor fieldsExtractor = new FieldsExtractor();

            docReport.extractFields(fieldsExtractor);
            fieldErrors = new BeanPropertyBindingResult(model, modelName);

            ValidationUtils.invokeValidator(reportValidator, fieldsExtractor, fieldErrors);

            if (fieldErrors.hasErrors()) {
                ReportProcessingException ex = new ReportProcessingException(
                        "Invalid report fields found", fieldErrors);
                throw ex;
            }
        } catch (XDocReportException | IOException ex) {
            throw new ReportProcessingException(ex);
        }
    }

    @Override
    public OutputStream generateReport(DocFormat targetFormat, Object model, ImageExtractObserver observer)
            throws ReportProcessingException {
        if (targetFormat == UNSUPPORTED) {
            throw new ReportProcessingException("Given format is not supported!");
        }

        if (!modelClass.isAssignableFrom(model.getClass())) {
            throw new ReportProcessingException("Given class is not implements: " + modelClass.getName());
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        DocConverterAdapter converter = null;
        try {
            validate(model);

            IContext context = getContext();
            context.put(modelName, model);
                    
            synchronized (DocReportTemplate.class) {
                docReport.process(context, out);
            }

            if (targetFormat != templateFormat) {
                InputStream in = new ByteArrayInputStream(out.toByteArray());

                converter = findConverter(targetFormat);
                if (converter != null) {

                    converter.addImageExtractObserver(observer);

                    out = (ByteArrayOutputStream) converter.convert(targetFormat, in);

                }
            }

        } catch (ReportProcessingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ReportProcessingException(ex);
        } finally {
            if (converter != null) {
                converter.removeImageExtractObserver(observer);
            }
        }

        return out;
    }

    public void setDocReportFactory(DocReportFactory docReportFactory) {
        this.docReportFactory = docReportFactory;
    }

    public String getTemplatePath() {
        try {
            return templateResource.getURL().getPath();
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        throw new IllegalStateException("Exception while getting the file");
    }

    public void setDocConverters(List<DocConverterAdapter> docConverters) {
        this.docConverters = docConverters;
    }

    public void setContextFactory(ContextFactory contextFactory) {
        this.contextFactory = contextFactory;
    }

    public Class<?> getModelClass() {
        return modelClass;
    }

    public void setModelClass(Class<?> modelClass) {
        this.modelClass = modelClass;
    }

    public void setBeforeRowToken(String beforeRowToken) {
        metadata.setBeforeRowToken(beforeRowToken);
    }

    public void setAfterRowToken(String afterRowToken) {
        metadata.setAfterRowToken(afterRowToken);
    }

    public void setBeforeTableCellToken(String beforeTableCellToken) {
        metadata.setBeforeTableCellToken(beforeTableCellToken);
    }

    public void setAfterTableCellToken(String afterTableCellToken) {
        metadata.setAfterTableCellToken(afterTableCellToken);
    }

    public void setIteratorNames(Map<String, Class<?>> iteratorNames) {
        this.iteratorNames = iteratorNames;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Errors getFieldErrors() {
        return fieldErrors;
    }

    public void setMetadataFillerChain(MetadataFillerChain metadataFillerChain) {
        this.metadataFillerChain = metadataFillerChain;
    }

    @Override
    public void setResourceLoader(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public void setTemplatePath(String templatePath) {
        this.templatePath = templatePath;
    }

    public void setSaxDriver(String saxDriver) {
        this.saxDriver = saxDriver;
    }
}
