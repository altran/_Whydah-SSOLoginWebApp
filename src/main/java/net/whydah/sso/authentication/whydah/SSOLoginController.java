package net.whydah.sso.authentication.whydah;

import net.whydah.sso.ServerRunner;
import net.whydah.sso.authentication.ModelHelper;
import net.whydah.sso.authentication.UserCredential;
import net.whydah.sso.config.AppConfig;
import net.whydah.sso.config.ApplicationMode;
import net.whydah.sso.usertoken.TokenServiceClient;
import net.whydah.sso.usertoken.UserTokenXpathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import java.util.UUID;

@Controller
public class SSOLoginController {
    public static final String DEFAULT_REDIRECT = "welcome";

    private final static Logger log = LoggerFactory.getLogger(SSOLoginController.class);
    private final TokenServiceClient tokenServiceClient;
    private String LOGOURL = "/sso/images/site-logo.png";
    private String APP_LINKS = "{}";
    private String whydahVersion = ServerRunner.version;

    //private final int MIN_REDIRECT_SIZE=4;
    //private final ModelHelper modelHelper = new ModelHelper(this);


    public SSOLoginController() throws IOException {
        Properties properties = AppConfig.readProperties();
        //String MY_APP_URI = properties.getProperty("myuri");
        LOGOURL = properties.getProperty("logourl");
        APP_LINKS = properties.getProperty("applinks");

        this.tokenServiceClient = new TokenServiceClient();
    }


    @RequestMapping("/login")
    public String login(HttpServletRequest request, HttpServletResponse response,Model model) {
        String redirectURI = getRedirectURI(request);

        model.addAttribute(SessionHelper.LOGO_URL, LOGOURL);
        model.addAttribute(SessionHelper.WHYDAH_VERSION,whydahVersion);
        model.addAttribute(SessionHelper.REDIRECT_URI, redirectURI);


        //usertokenId = cookieManager.getUserTokenIdFromCookie(request, response);
        String userTokenIdFromCookie = CookieManager.getUserTokenIdFromCookie(request);
        log.trace("login: redirectURI={}, userTokenIdFromCookie={}", redirectURI, userTokenIdFromCookie);

        WhydahUserTokenId whydahUserTokenId = WhydahUserTokenId.invalidTokenId();
        if ("logout".equalsIgnoreCase(userTokenIdFromCookie)) {
            //TODO ED: Needs to be reviewed/changed - will never happend AFAIK
            log.info("userTokenId={} from cookie. TODO: should probably clear the logout cookie here?", userTokenIdFromCookie);
            CookieManager.clearUserTokenCookies(request, response);
            //usertokenId = WhydahUserTokenId.invalidTokenId();
        } else if (userTokenIdFromCookie != null && tokenServiceClient.verifyUserTokenId(userTokenIdFromCookie)) {
            log.trace("userTokenId={} from cookie verified OK.", userTokenIdFromCookie);
            whydahUserTokenId = WhydahUserTokenId.fromTokenId(userTokenIdFromCookie);
        } else {
            CookieManager.clearUserTokenCookies(request, response);
        }

        if (whydahUserTokenId.isValid()) {
            log.trace("login - whydahUserTokenId={} is valid", whydahUserTokenId);

            if (DEFAULT_REDIRECT.equalsIgnoreCase(redirectURI)){
                log.trace("login - Did not find any sensible redirectURI, using /welcome");
                model.addAttribute(SessionHelper.REDIRECT, redirectURI);
                log.info("login - Redirecting to {}", redirectURI);
                return "action";

            }
            String userTicket = UUID.randomUUID().toString();
            if (tokenServiceClient.createTicketForUserTokenID(userTicket, whydahUserTokenId.toString())){
                log.info("login - created new userticket={} for usertokenid={}",userTicket, whydahUserTokenId);
                redirectURI = tokenServiceClient.appendTicketToRedirectURI(redirectURI, userTicket);

                // Action use redirect - not redirectURI
                model.addAttribute(SessionHelper.REDIRECT, redirectURI);
                log.info("login - Redirecting to {}", redirectURI);
                return "action";
            }
        }
        ModelHelper.setEnabledLoginTypes(model);
        return "login";
    }



    @RequestMapping("/welcome")
    public String welcome(HttpServletRequest request, HttpServletResponse response,Model model) {
        String userTicket = request.getParameter(TokenServiceClient.USERTICKET);
        String userTokenId = CookieManager.getUserTokenIdFromCookie(request);
        String userToken;
        model.addAttribute(SessionHelper.LOGO_URL, LOGOURL);
        model.addAttribute(SessionHelper.IAM_MODE, ApplicationMode.getApplicationMode());
        model.addAttribute(SessionHelper.WHYDAH_VERSION,whydahVersion);
        try {
            if (userTicket != null && userTicket.length() > 3) {
                log.trace("Welcome - Using userTicket");
                userToken = tokenServiceClient.getUserTokenByUserTicket(userTicket);
                model.addAttribute(TokenServiceClient.USERTICKET, userTicket);
                model.addAttribute(TokenServiceClient.USER_TOKEN_ID, UserTokenXpathHelper.getUserTokenId(userToken));
            } else if (userTokenId != null && userTokenId.length() > 3) {
                log.trace("Welcome - No userTicket, using userTokenID from cookie");
                userToken = tokenServiceClient.getUserTokenByUserTokenID(userTokenId);
                model.addAttribute(TokenServiceClient.USERTICKET, "No userTicket, using userTokenID");
                model.addAttribute(TokenServiceClient.USER_TOKEN_ID, userTokenId);
            } else {
                throw new UnauthorizedException();
            }
        } catch (Exception e){
            log.warn("welcome redirect - SecurityTokenException exception: ",e);
            ModelHelper.setEnabledLoginTypes(model);
            return "login";
        }
        model.addAttribute(TokenServiceClient.USERTOKEN, trim(userToken));
        model.addAttribute(SessionHelper.APP_LINKS, APP_LINKS);
        model.addAttribute(TokenServiceClient.REALNAME, UserTokenXpathHelper.getRealName(userToken));
        return "welcome";
    }

    @RequestMapping("/action")
    public String action(HttpServletRequest request, HttpServletResponse response, Model model) {
        UserCredential user = new UserNameAndPasswordCredential(request.getParameter(SessionHelper.USER), request.getParameter(SessionHelper.PASSWORD));
        String redirectURI = getRedirectURI(request);
        log.trace("action: redirectURI: {}", redirectURI);
        model.addAttribute(SessionHelper.LOGO_URL, LOGOURL);
        model.addAttribute(SessionHelper.WHYDAH_VERSION,whydahVersion);
        String userTicket = UUID.randomUUID().toString();
        String userTokenXml = tokenServiceClient.getUserToken(user, userTicket);

        if (userTokenXml == null) {
            log.warn("action - getUserToken failed. Redirecting to login.");
            model.addAttribute(SessionHelper.LOGIN_ERROR, "Could not log in.");
            ModelHelper.setEnabledLoginTypes(model);
            model.addAttribute(SessionHelper.REDIRECT_URI, redirectURI);
            return "login";
        }
        if (redirectURI.contains(TokenServiceClient.USERTICKET)) {
            log.warn("action - redirectURI contain ticket. Redirecting to welcome.");
            model.addAttribute(SessionHelper.LOGIN_ERROR, "Could not redirect back, redirect loop detected.");
            ModelHelper.setEnabledLoginTypes(model);
            model.addAttribute(SessionHelper.REDIRECT_URI, "");
            return "welcome";
        }

        String userTokenId = UserTokenXpathHelper.getUserTokenId(userTokenXml);
        Integer tokenRemainingLifetimeSeconds = TokenServiceClient.calculateTokenRemainingLifetimeInSeconds(userTokenXml);
        CookieManager.createAndSetUserTokenCookie(userTokenId, tokenRemainingLifetimeSeconds, response);

        // ticket on redirect
        if (redirectURI.toLowerCase().contains(SessionHelper.USERTICKET)) {
            // Do not overwrite ticket
        } else {
            redirectURI = tokenServiceClient.appendTicketToRedirectURI(redirectURI, userTicket);
        }
        // Action use redirect...
        model.addAttribute(SessionHelper.REDIRECT, redirectURI);
        model.addAttribute(SessionHelper.REDIRECT_URI, redirectURI);
        log.info("action - Redirecting to {}", redirectURI);
        return "action";
    }



    private String getRedirectURI(HttpServletRequest request) {
        String redirectURI = request.getParameter(SessionHelper.REDIRECT_URI);
        //log.trace("getRedirectURI - redirectURI from request: {}", redirectURI);
        if (redirectURI == null || redirectURI.length() < 1) {
            log.trace("getRedirectURI - No redirectURI found, setting to {}", DEFAULT_REDIRECT);
            return DEFAULT_REDIRECT;
        }
        return redirectURI;
    }


    public static String trim(String input) {
        BufferedReader reader = new BufferedReader(new StringReader(input));
        StringBuffer result = new StringBuffer();
        try {
            String line;
            while ( (line = reader.readLine() ) != null)
                result.append(line.trim());
            return result.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}


