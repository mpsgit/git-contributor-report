package hu.devreport;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;

/** Shared FreeMarker rendering infrastructure for presentation templates. */
final class TemplateRenderer {
    private static final Configuration CONFIGURATION = configuration();

    private TemplateRenderer() { }

    static String page(String title, String resourcePrefix, String body) {
        return render("page.ftlh", Map.of(
                "title", title,
                "resourcePrefix", resourcePrefix,
                "body", body
        ));
    }

    private static String render(String templateName, Map<String, ?> model) {
        try {
            Template template = CONFIGURATION.getTemplate(templateName);
            StringWriter output = new StringWriter();
            template.process(model, output);
            return output.toString();
        } catch (IOException | TemplateException e) {
            throw new IllegalStateException("A sablon nem renderelhető: " + templateName, e);
        }
    }

    private static Configuration configuration() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_35);
        configuration.setClassLoaderForTemplateLoading(TemplateRenderer.class.getClassLoader(), "templates");
        configuration.setDefaultEncoding("UTF-8");
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        return configuration;
    }
}
