/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.appdynamics.appdrestapi.resources;

import org.appdynamics.appdrestapi.data.AutoDiscoveryConfig;
import org.appdynamics.appdrestapi.data.*;
import org.appdynamics.appdrestapi.exportdata.*;


import com.sun.jersey.multipart.FormDataMultiPart;
import com.sun.jersey.multipart.MultiPart;
import com.sun.jersey.multipart.FormDataBodyPart;
import com.sun.jersey.multipart.file.FileDataBodyPart;


import com.sun.jersey.api.client.Client;
import com.sun.jersey.client.urlconnection.URLConnectionClientHandler;

import com.sun.jersey.client.urlconnection.HttpURLConnectionFactory;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.client.urlconnection.HTTPSProperties;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.NewCookie;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import java.io.InputStream;
import java.io.ByteArrayInputStream;

//Accepting all certs
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.net.InetSocketAddress;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.io.IOException;

import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;
import org.appdynamics.appdrestapi.queries.AuthActionQuery;
import org.codehaus.jackson.map.ObjectMapper;



/**
 *
 * @author gilbert.solorzano
 * 
 * <p>
 * The executor handles the process of communicating with the controller and retrieving the data.
 * </p>
 */

/*
    Initially we created a static 
*/
public class RESTExecuter {
    private com.sun.jersey.api.client.config.ClientConfig config = null;
    private com.sun.jersey.api.client.Client client=null;
    private static Logger logger=Logger.getLogger(RESTExecuter.class.getName());
    private String baseURL;
    private List<NewCookie> cookies;
    private static String CSRF;
    
    public RESTExecuter(String baseURL){this.baseURL=baseURL;}
    
    private void createConnection(RESTAuth auth) throws Exception{
        
        /* This was added to insure that we can be used with controllers that have a secure setting.*/
        System.setProperty("https.protocols", "TLSv1.2");
        config = new DefaultClientConfig();
        config.getFeatures().put(JSONConfiguration.FEATURE_POJO_MAPPING, Boolean.TRUE);

        
        if(s.debugLevel > 3)logger.log(Level.INFO,new StringBuilder().append("Using the following for auth: ").append(auth.toString()).toString());
        
        /* We need to accept self signed certificates */
        TrustManager[] certs = new TrustManager[]{
            new X509TrustManager(){
                @Override
                public X509Certificate[] getAcceptedIssuers(){
                    return null;
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException{}

                @Override 
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException{}
            }  
        };
        
        SSLContext ctx = null;
        //logger.log(Level.SEVERE,"Setting SSLContext");
        try{
            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, certs, new SecureRandom());
        }catch(java.security.GeneralSecurityException ex){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception ocurred while attempting to setup all trusting SSL security. ").toString());
        }
        
        //logger.log(Level.SEVERE,"Setting url connection!");
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
        
        try{
            config.getProperties().put(HTTPSProperties.PROPERTY_HTTPS_PROPERTIES, new HTTPSProperties(
                    new HostnameVerifier(){
                        @Override
                        public boolean verify(String hostname, SSLSession session){return true;}
                    },ctx));
            
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception ocurred while attempting to associating our SSL cert to the session.").toString());
        }
        //old code
        //logger.log(Level.SEVERE,"Near the end. " + config.getProperties().toString());
        try{
            /* This will cover when we are using cookies */
            if(auth.isUseProxy()){
                System.setProperty(s.HTTP_PROXYHOST, auth.getProxy().getHost());
                System.setProperty(s.HTTP_PROXYPORT, auth.getProxy().getPort().toString());
                
                client = new Client(new URLConnectionClientHandler(
                        new HttpURLConnectionFactory(){
                            Proxy p = null;
                            @Override
                            public HttpURLConnection getHttpURLConnection(URL url) throws IOException{
                                if( p == null){
                                    p = new Proxy(Proxy.Type.HTTP, 
                                            new InetSocketAddress(System.getProperty(s.HTTP_PROXYHOST), new Integer(System.getProperty(s.HTTP_PROXYPORT)))
                                            );
                                }
                                
                                return (HttpURLConnection) url.openConnection(p);
                            }
                })  ,config);
            }else{
                client = Client.create(config);
            }
            client.addFilter(new HTTPBasicAuthFilter(auth.getUserNameForAuth(),auth.getPasswd()));
        }catch(Exception e){
            StringBuilder bud = new StringBuilder();
            for(StackTraceElement st: e.getStackTrace()){
                bud.append(st.toString()).append("\n");
            }
            throw new Exception(new StringBuilder().append("Exception occurred while attempting to create connection object. Exception: ")
                    .append(e.getMessage()).append("\nStackTraceElements:\n").append(bud.toString()).toString());
        }
        
        if(client == null) throw new Exception(new StringBuilder().append("Unable to create connection object, creation attempt returned NULL.").toString());
        setupCookieAuth(); //This will get the cookies
    }
    
    private void setupCookieAuth(){
        /* With the new rest URLS we need to use the cookie, expect the client not to be null*/
        if( client != null){
          
            String query = AuthActionQuery.queryAuthAction(baseURL);
            if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
            WebResource service = client.resource(query);
            ClientResponse response = null;
            MetricDatas md = null;
            try{
                int currentCount=1;
                while(currentCount <= s.MAX_TRIES){
                response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
                    if(response.getStatus() >= 500){
                        logger.log(Level.INFO,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                                .append(", attempting again from attempt number ").append(currentCount).toString());

                        currentCount++;
                        Thread.sleep(1200*currentCount);
                    }else{
                        //Now we need to get the cookies.
                        cookies=response.getCookies();
                        currentCount=s.MAX_TRIES+1;
                    }
                }

                if(response.getStatus() >= 500 && currentCount > s.MAX_TRIES) 
                    logger.log(Level.SEVERE,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                                .append(".\nUnable to get a proper response for query:\n").append(query).toString());

            }catch(Exception e){
                logger.log(Level.SEVERE,new StringBuilder().append("Exception getting entity. \nQuery:\n\t")
                        .append(query).append("\nError:").append(e.getMessage()).append(".\n Response code is ")
                        .append(response.getStatus()).toString());
            }
        }else{
            logger.log(Level.SEVERE,"The connection is null, something has gone wrong.");
        }
    }
    
    private WebResource.Builder setCookies(WebResource web){
        if(s.debugLevel > 1) logger.log(Level.INFO,"Setting cookies");
        WebResource.Builder builder = web.getRequestBuilder();
        if( cookies != null){
            java.util.ListIterator<NewCookie> iter = cookies.listIterator();
            while(iter.hasNext()) {
                NewCookie cok = iter.next();
                if(cok != null && cok.toCookie().getName().equals("X-CSRF-TOKEN")) CSRF=cok.toCookie().getValue();
                if(s.debugLevel > 1) logger.info(cok.toString());
                builder.cookie(cok);
            }
        }
        return builder;
    }
    
    
    public  MetricDatas executeMetricQuery(RESTAuth auth, String query)throws Exception{
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = client.resource(query);
        WebResource.Builder service = setCookies(service1);
        ClientResponse response = null;
        MetricDatas md = null;
        try{
            int currentCount=1;
            while(currentCount <= s.MAX_TRIES){
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
                if(response.getStatus() >= 500){
                    logger.log(Level.INFO,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                            .append(", attempting again from attempt number ").append(currentCount).toString());
                    
                    currentCount++;
                    Thread.sleep(1200*currentCount);
                }else{
                    md = (MetricDatas) response.getEntity(MetricDatas.class);
                    if(md == null){ 
                        logger.log(Level.INFO,new StringBuilder().append("Data returned was null, attempting the query again. ").toString());
                        currentCount++;}
                    else{currentCount=s.MAX_TRIES+1;}
                }
            }
            
            if(response.getStatus() >= 500 && currentCount > s.MAX_TRIES) 
                logger.log(Level.SEVERE,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                            .append(".\nUnable to get a proper response for query:\n").append(query).toString());
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting entity. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\n Response code is ")
                    .append(response.getStatus()).toString());
        }
        
        
        
        if(s.debugLevel > 1 && md != null){
            logger.log(Level.INFO,new StringBuilder().append("Number of metrics datas returns is ").append(md.getMetric_data().size()).toString());
        }
        
        if(s.debugLevel > 2 && md != null) logger.log(Level.FINE,new StringBuilder().append(md.toString()).toString());
        return md;
    }
    
    public Dashboard executeDashboardObjExportByIdQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        ExDashboard val=null;
        Dashboard dash=null;
        try{
         
             service1 = client.resource(query);
             WebResource.Builder service = setCookies(service1);
            
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            //logger.log(Level.INFO, new StringBuilder().append("responsecode:::").append(response.getStatus()).toString());
            if(response.getStatus() == 200){
                value= (String) response.getEntity(String.class);
                JAXBContext context = JAXBContext.newInstance(ExDashboard.class);
                Unmarshaller un = context.createUnmarshaller();
                InputStream inStream = new ByteArrayInputStream(value.getBytes());
                val = (ExDashboard) un.unmarshal(inStream);
            
                dash=new Dashboard();
                dash.setExists(true);
                dash.setName(val.getName());
                dash.setValue(value);
                //logger.log(Level.INFO,val.toString());
            }else{
                dash = new Dashboard();
                dash.setExists(false);
            }

            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting dashboard export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return dash;
        
    }
    
    public String executeDashboardList(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
  
        try{
         
             service1 = client.resource(query);
             WebResource.Builder service = setCookies(service1);
            
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            //logger.log(Level.INFO, new StringBuilder().append("responsecode:::").append(response.getStatus()).toString());
            if(response.getStatus() == 200){
                value= (String) response.getEntity(String.class);
                ObjectMapper mapper = new ObjectMapper();
                DashboardList dashList = mapper.readValue(value, DashboardList.class);
                logger.log(Level.INFO,dashList.toString());
            }

            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting dashboard export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return value;
        
    }
    
    public String executeDashboardExportByIdQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
  
        try{
         
             service1 = client.resource(query);
             WebResource.Builder service = setCookies(service1);
            
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            //logger.log(Level.INFO, new StringBuilder().append("responsecode:::").append(response.getStatus()).toString());
            if(response.getStatus() == 200){
                value= (String) response.getEntity(String.class);
                ObjectMapper mapper = new ObjectMapper();
                DashboardList dashList = mapper.readValue(value, DashboardList.class);
                logger.log(Level.INFO,dashList.toString());
            }

            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting dashboard export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return value;
        
    }
    
    public String executeApplicationExportByIdQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            value= (String) response.getEntity(String.class);
            
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return value;
        
    }
    
    public ExApplication executeApplicationExportObjByIdQuery(RESTAuth auth, String query)throws Exception {
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        String apps=null;
        ExApplication exApp=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            apps= (String) response.getEntity(String.class);
            JAXBContext context = JAXBContext.newInstance(ExApplication.class);
            Unmarshaller un = context.createUnmarshaller();
            InputStream inStream = new ByteArrayInputStream(apps.getBytes());
            exApp = (ExApplication) un.unmarshal(inStream);
  
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return exApp;
        
    }
    
    public BusinessTransactions executeBusinessTransactionQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        BusinessTransactions bts=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            bts= (BusinessTransactions) response.getEntity(BusinessTransactions.class);
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting business transaction: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        
        
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of metrics datas returns is ").append(bts.getBusinessTransactions().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(bts.toString()).toString());
        
        return bts;
    }
    
    public  Applications executeApplicationQuery(RESTAuth auth, String query) throws Exception{
        
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        Applications apps=null;
        try{
         

            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);

            
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            //System.out.println("About to execute!");
            apps= (Applications) response.getEntity(Applications.class);
            
        }catch(Exception e){
            StringBuilder bud=new StringBuilder();
            bud.append("Exception getting entity: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage())
                    .append(".\nResponse code is ").append(response.getStatus());
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting entity: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
            
            throw new Exception(bud.toString());
        }
        
        if(apps == null){
            StringBuilder bud=new StringBuilder();
            bud.append("Application object is null: \nQuery:\n\t")
                    .append(query).append(".\nResponse code is ").append(response.getStatus());
    
            
            throw new Exception(bud.toString());
        }
        
        if(s.debugLevel > 1 ){
            logger.log(Level.INFO,new StringBuilder().append("Number of applications datas returns is ").append(apps.getApplications().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(apps.toString()).toString());
        
        return apps;
    }
    
    public Tiers executeTierQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null; //client.resource(query);
        
        ClientResponse response = null;
        Tiers tiers= null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            tiers= (Tiers) response.getEntity(Tiers.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting entity: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code is ")
                    .append(response.getStatus()).toString());
        }
        

        return tiers;
    }
    
    public Nodes executeNodeQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;//client.resource(query);
        ClientResponse response = null;
        Nodes nodes=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            nodes= (Nodes) response.getEntity(Nodes.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of metrics datas returns is ").append(nodes.getNodes().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(nodes.toString()).toString());
        
        
        return nodes;
    }
    
    public PolicyViolations executePolicyViolations(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;//client.resource(query);
        ClientResponse response = null;
        PolicyViolations pvs=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            pvs= (PolicyViolations) response.getEntity(PolicyViolations.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of policy violations returns is ").append(pvs.getPolicyViolations().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(pvs.toString()).toString());
        
        return pvs;
    }
    
    public Events executeEvents(RESTAuth auth, String query) throws Exception{
        
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;//client.resource(query);
        
        ClientResponse response = null;
        Events evs=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            evs= (Events) response.getEntity(Events.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
          
        if(s.debugLevel > 1 && evs != null ){
            logger.log(Level.INFO,new StringBuilder().append("Number of events returns is ").append(evs.getEvents().size()).toString());
        }
        
        if(s.debugLevel > 2 && evs != null) logger.log(Level.FINE,new StringBuilder().append(evs.toString()).toString());
        
        return evs;
    }
    

    public Backends executeBackends(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;//client.resource(query);
        ClientResponse response = null;
        Backends bcs=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            bcs= (Backends) response.getEntity(Backends.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of events returns is ").append(bcs.getBackend().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(bcs.toString()).toString());
        
        return bcs;
    }
    
    public Snapshots executeSnapshots(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;//client.resource(query);
        ClientResponse response = null;
        Snapshots rs=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            rs= (Snapshots) response.getEntity(Snapshots.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of snapshots returns is ").append(rs.getRequestDatas().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(rs.toString()).toString());
        
        return rs;
    }
    
    public MetricItems executeMetricItems(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;//client.resource(query);
        ClientResponse response = null;
        MetricItems mi=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            mi= (MetricItems) response.getEntity(MetricItems.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of metricItems returns is ").append(mi.getMetricItems().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(mi.toString()).toString());
        
        return mi;
    }
    

    // Working on this one
    public AutoDiscoveryConfig executeExportAuto(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).append("\n\n").toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        AutoDiscoveryConfig value=null;
        String export=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            export= (String) response.getEntity(String.class);
            
            JAXBContext context = JAXBContext.newInstance(AutoDiscoveryConfig.class);
            Unmarshaller un = context.createUnmarshaller();
            InputStream inStream = new ByteArrayInputStream(export.getBytes());
            value = (AutoDiscoveryConfig) un.unmarshal(inStream);
            
            
        }catch(Exception e){
            StringBuilder bud = new StringBuilder();
            bud.append("\n   Exception getting Transaction Detection Auto export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus());
            
            throw new Exception(bud.toString());
        }
        
        return value;
    }
    
    // Working on this one later
    public String executeAutoPostQuery(RESTAuth auth, String query, String entityName, String xml) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        
        try{
            
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            
            FormDataMultiPart form=new FormDataMultiPart();
            form.bodyPart(new FormDataBodyPart("name",new StringBuilder().append(entityName).append(".xml").toString()));
            form.bodyPart(new FormDataBodyPart("filename", xml, MediaType.WILDCARD_TYPE));
            
            response = service.type(MediaType.MULTIPART_FORM_DATA_TYPE).post(ClientResponse.class,form);
            
            
            if(response.getStatus() >= 500) 
                logger.log(Level.SEVERE,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                            .append(".\nUnable to get a proper response for query:\n").append(query).toString());
            
            value=new StringBuilder().append("Response was ").append(response.getStatus()).append(".").toString();
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        
        return value;
    }
    
    public String executePostDashboardQuery(RESTAuth auth, String query, String filePath) throws Exception{
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        
        java.io.File file = new java.io.File(filePath);
        if(!file.exists() || !file.canRead()){
            logger.log(Level.SEVERE,  new StringBuilder().append("Either the file '").append(filePath).append("' does not exist or it is not readable.").toString());
            throw new Exception(new StringBuilder().append("Either the file '").append(filePath).append("' does not exist or it is not readable.").toString());
        }
        
        try{
            
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);            

            FormDataMultiPart form=new FormDataMultiPart();
            form.bodyPart(new FormDataBodyPart("X-CSRF-TOKEN",CSRF));
            
            MultiPart multiPart = form.bodyPart(new FileDataBodyPart("file", file, MediaType.APPLICATION_JSON_TYPE));
            response = service.type(MediaType.MULTIPART_FORM_DATA_TYPE).post(ClientResponse.class,multiPart);
            
            if(response.getStatus() >= 500){ 
                logger.log(Level.SEVERE,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                            .append(".\nUnable to get a proper response for query:\n").append(query).toString());
                 if(response.hasEntity()) logger.log(Level.SEVERE, (String) response.getEntity(String.class));
            }
            //value=new StringBuilder().append("Response was ").append(response.getStatus()).append(".").toString();
            if(response.hasEntity()) value =  (String) response.getEntity(String.class);
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        
        return value;
    }
    
    // Working on this one later
    public String executeAutoPostQueryJSON(RESTAuth auth, String query, String entityName, String json) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        
        try{
            
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            
            FormDataMultiPart form=new FormDataMultiPart();
            form.bodyPart(new FormDataBodyPart("name",new StringBuilder().append(entityName).append(".json").toString()));
            form.bodyPart(new FormDataBodyPart("filename", json, MediaType.WILDCARD_TYPE));
            
            response = service.type(MediaType.MULTIPART_FORM_DATA_TYPE).post(ClientResponse.class,form);
            
            
            if(response.getStatus() >= 500) 
                logger.log(Level.SEVERE,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                            .append(".\nUnable to get a proper response for query:\n").append(query).toString());
            
            value=new StringBuilder().append("Response was ").append(response.getStatus()).append(".").toString();
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        
        return value;
    }
 
    public String executeTDQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());        
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            value= (String) response.getEntity(String.class);
            
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting Transaction Detection export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        
        return value;
    }
    
    public CustomMatchPoints executeTDObjQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
         
        CustomMatchPoints value=null;
        String export=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            export= (String) response.getEntity(String.class);
            
            JAXBContext context = JAXBContext.newInstance(CustomMatchPoints.class);
            Unmarshaller un = context.createUnmarshaller();
            InputStream inStream = new ByteArrayInputStream(export.getBytes());
            value = (CustomMatchPoints) un.unmarshal(inStream);
            
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting Transaction Detection export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        
        return value;
    }
    
    public String executeExportHealthRule(RESTAuth auth, String query) throws Exception{
        
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        try{
  
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            value= (String) response.getEntity(String.class);
            
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return value;
        
    }
    
    public HealthRules executeExportHealthRuleObj(RESTAuth auth, String query) throws Exception{
        
        if(client == null) {
            createConnection(auth);
        }
        
        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        HealthRules value=null;
        String export=null;
        try{
         
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            export= (String) response.getEntity(String.class);
            
            JAXBContext context = JAXBContext.newInstance(HealthRules.class);
            Unmarshaller un = context.createUnmarshaller();
            InputStream inStream = new ByteArrayInputStream(export.getBytes());
            value = (HealthRules) un.unmarshal(inStream);
            
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ").append(response.getStatus()).toString());
        }
        return value;
        
    }
    
    public String executeEventPostQuery(RESTAuth auth, String query) throws Exception{
        if(client == null) {
            createConnection(auth);
        }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        WebResource service1 = null;
        ClientResponse response = null;
        String value=null;
        
        try{
            
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.type(MediaType.APPLICATION_XML).post(ClientResponse.class);
   
            if(response.getStatus() >= 400) 
                logger.log(Level.SEVERE,new StringBuilder().append("Caught HTTP error number ").append(response.getStatus())
                            .append(".\nUnable to get a proper response for query:\n").append(query).toString());
            
            value=new StringBuilder().append("Response was ").append(response.getStatus()).append(".").toString();
            
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder().append("Exception getting application export: \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(".\nResponse code is ")
                    .append(response.getStatus()).toString());
        }
        
        return value;
    }
    
    public ConfigurationItems executeConfigurationItems(RESTAuth auth, String query) throws Exception{
        if(client == null) {    createConnection(auth);      }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        ConfigurationItems mi=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_XML).get(ClientResponse.class);
            mi= (ConfigurationItems) response.getEntity(ConfigurationItems.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("Number of ConfigurationItems returns is ")
                    .append(mi.getConfigurationItems().size()).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(mi.toString()).toString());
        
        return mi;
    }
    
    public LicenseProperties executeLicenseProperties(RESTAuth auth, String query) throws Exception{
        if(client == null) {    createConnection(auth);      }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        LicenseProperties mi=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_JSON).get(ClientResponse.class);
            mi= (LicenseProperties) response.getEntity(LicenseProperties.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("This is the license information returned ")
                    .append(mi).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(mi).toString());
        
        return mi;
    }
    
    
    public AccountEUM executeAccountEUM(RESTAuth auth, String query) throws Exception{
        if(client == null) {    createConnection(auth);      }

        if(s.debugLevel > 1)logger.log(Level.INFO,new StringBuilder().append("\nExecuting query: ").append(query).toString());
        
        
        WebResource service1 = null;
        ClientResponse response = null;
        AccountEUM mi=null;
        try{
            //service = client.resource(query);
            service1 = client.resource(query);
            WebResource.Builder service = setCookies(service1);
            response = service.accept(MediaType.APPLICATION_JSON).get(ClientResponse.class);
            mi= (AccountEUM) response.getEntity(AccountEUM.class);
        }catch(Exception e){
            logger.log(Level.SEVERE,new StringBuilder()
                    .append("Exception getting entity, please insure that your query is correct. \nQuery:\n\t")
                    .append(query).append("\nError:").append(e.getMessage()).append(". Response code ")
                    .append(response.getStatus()).toString());
        } 
        
        if(s.debugLevel > 1){
            logger.log(Level.INFO,new StringBuilder().append("This is the license information returned ")
                    .append(mi).toString());
        }
        
        if(s.debugLevel > 2) logger.log(Level.FINE,new StringBuilder().append(mi).toString());
        
        return mi;
    }
    
}
