package net.whydah.sso.useradmin;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import net.whydah.sso.config.AppConfig;
import net.whydah.sso.usertoken.TokenServiceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.net.URI;
import java.util.Properties;

/**
 * Password management self service.
 */
@Controller
public class PasswordChangeController {
    private static final Logger log = LoggerFactory.getLogger(PasswordChangeController.class);
    private static final Client uasClient = Client.create();
    private URI uasServiceUri;
    private final TokenServiceClient tokenServiceClient = new TokenServiceClient();
    String LOGOURL = "/sso/images/site-logo.png";
    String MY_APP_URI = "";


    //TODO Should go via UAS.
    public PasswordChangeController() throws IOException {
        uasServiceUri = UriBuilder.fromUri(AppConfig.readProperties().getProperty("useradminservice")).build();
        Properties properties = AppConfig.readProperties();
        String MY_APP_URI = properties.getProperty("myuri");
        LOGOURL = properties.getProperty("logourl");
    }

    @RequestMapping("/test/*")
    public String t1(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @RequestMapping("/resetpassword")
    public String resetpassword(HttpServletRequest request, Model model) {
        log.trace("resetpassword was called");
        model.addAttribute("logoURL", LOGOURL);
        String user = request.getParameter("username");
        if (user == null) {
            return "resetpassword";
        }

        model.addAttribute("logoURL", LOGOURL);
        WebResource uasWR = uasClient.resource(uasServiceUri).path(tokenServiceClient.getMyAppTokenID()+"/auth/password/reset/username/" + user);
        ClientResponse response = uasWR.type(MediaType.APPLICATION_JSON).post(ClientResponse.class);
        if (response.getStatus() != ClientResponse.Status.OK.getStatusCode()) {
            String error = response.getEntity(String.class);
            log.error(error);
            model.addAttribute("error", error+"\nusername:"+user);
            return "resetpassword";
        }
        return "resetpassworddone";
    }

    @RequestMapping("/changepassword/*")
    public String changePasswordFromLink(HttpServletRequest request, Model model) {
        log.trace("changePasswordFromLink was called");
        model.addAttribute("logoURL", LOGOURL);
        PasswordChangeToken passwordChangeToken = getTokenFromPath(request);
        model.addAttribute("username", passwordChangeToken.getUser());
        model.addAttribute("token", passwordChangeToken.getToken());
        if (!passwordChangeToken.isValid()) {
            return "changepasswordtokentimedout";
        } else {
            return "changepassword";
        }
    }

    @RequestMapping("/dochangepassword/*")
    public String doChangePasswordFromLink(HttpServletRequest request, Model model) {
        log.trace("doChangePasswordFromLink was called");
        // +@Path("/password/{applciationtokenid}")   @Path("/reset/username/{username}")
        model.addAttribute("logoURL", LOGOURL);
        PasswordChangeToken passwordChangeToken = getTokenFromPath(request);
        String newpassword = request.getParameter("newpassword");
//        WebResource uibWR = uibClient.resource(uibServiceUri).path("/password/" + tokenServiceClient.getMyAppTokenID() + "/reset/username/" + passwordChangeToken.getUser() + "/newpassword/" + passwordChangeToken.getToken());
        WebResource uasWR = uasClient.resource(uasServiceUri).path(tokenServiceClient.getMyAppTokenID()+"/auth/password/reset/username/" + passwordChangeToken.getUser() + "/newpassword/" + passwordChangeToken.getToken());
        log.trace("doChangePasswordFromLink was called. Calling UAS with url " + uasWR.getURI());

        ClientResponse response = uasWR.type(MediaType.APPLICATION_JSON).post(ClientResponse.class, "{\"newpassword\":\"" + newpassword + "\"}");
        model.addAttribute("logoURL", LOGOURL);
        model.addAttribute("username", passwordChangeToken.getUser());
        if (response.getStatus() != ClientResponse.Status.OK.getStatusCode()) {
            String error = response.getEntity(String.class);
            log.error(error);
            if(response.getStatus() == ClientResponse.Status.NOT_ACCEPTABLE.getStatusCode()) {
                model.addAttribute("error", "The password you entered was found to be too weak, please try another password.");
            } else {
                model.addAttribute("error", error+"\nusername:"+passwordChangeToken.getUser());
            }
            model.addAttribute("token", passwordChangeToken.getToken());
            return "changepassword";
        }
        return "changedpassword";
    }

    private PasswordChangeToken getTokenFromPath(HttpServletRequest request) {
        String path = request.getPathInfo();
        String tokenString = path.substring(path.lastIndexOf('/') + 1);
        return new PasswordChangeToken(tokenString);
    }

}
