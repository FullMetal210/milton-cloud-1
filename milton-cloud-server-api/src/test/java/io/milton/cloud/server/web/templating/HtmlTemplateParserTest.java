package io.milton.cloud.server.web.templating;

import io.milton.common.Path;
import java.io.InputStream;
import java.net.URL;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;



import org.junit.Test;
import static org.junit.Assert.*;

/**
 *
 * @author brad
 */
public class HtmlTemplateParserTest {
    
    @Test
    public void testParse() throws Exception {
        HtmlTemplateParser parser = new HtmlTemplateParser();
        URL resource = this.getClass().getResource("/test.html");
        HtmlPage htmlPage = new ClassPathTemplateHtmlPage(resource);
        parser.parse(htmlPage, Path.root);
        
        WebResource wrScript = null;
        WebResource wrParam = null;
        
        for( WebResource wr : htmlPage.getWebResources()) {
            if( wr.getTag().equals("script")) {
                String type = wr.getAtts().get("type");
                if( type != null && type.equals("data/parameter")) {
                    wrParam = wr;
                }
                if( type != null && type.equals("text/javascript")) {
                    wrScript = wr;
                }
            }
        }
        assertNotNull(wrParam);
        assertNotNull(wrScript);
        assertEquals("//", wrScript.getBody());
        assertTrue(wrParam.getBody().startsWith("<p>Registering as a Professional"));
    }
}
