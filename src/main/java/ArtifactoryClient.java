import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HostConfiguration;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.MultiThreadedHttpConnectionManager;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.params.HttpConnectionManagerParams;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public class ArtifactoryClient
{
    private static final String API_URL = "http://localhost:8081/artifactory/api";

    private static final DateTimeFormatter JSON_DATE_FORMAT = DateTimeFormatter.ofPattern( "yyyy-MM-dd'T'HH:mm:ss.SSSZ" );

    private static final TypeReference<HashMap<String,Object>> TYPE_REF = new TypeReference<>() {};
    private static final TypeReference<Object[]> TYPE_REF2 = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final class Item {

        private final boolean isFolder;
        public final String path;
        public long sizeInBytes;
        public ZonedDateTime lastUpdated = null;

        public Item(String path, boolean isFolder)
        {
            Validate.notBlank( path, "path must not be null or blank");
            this.isFolder = isFolder;
            this.path = path;
        }

        public boolean isFolder() {
            return isFolder;
        }
    }


    public static final class Repository
    {
        public final String repoId;
        private final String type;

        public Repository(String repoId, String type)
        {
            Validate.notBlank(repoId,"repo ID must not be NULL/blank");
            Validate.notBlank( type, "type must not be null or blank");
            this.type = type;
            this.repoId = repoId;
        }

        public boolean isRemote() {
            return "REMOTE".equalsIgnoreCase( type );
        }

        public boolean isVirtual() {
            return "VIRTUAL".equalsIgnoreCase( type );
        }

        public boolean isLocal() {
            return "LOCAL".equalsIgnoreCase( type );
        }
    }

    private HttpClient client;

    private int maxHostConnections = 10;
    private String apiUrl;
    private String user;
    private String password;

    private boolean needsReconnect = true;
    private boolean isConnected = false;

    public ArtifactoryClient() {
    }

    public void setApiUrl(String url)
    {
        this.needsReconnect |= ! Objects.equals(this.apiUrl,url);
        this.apiUrl = url;
    }

    public void setMaxHostConnections(int maxHostConnections)
    {
        Validate.isTrue( maxHostConnections >= 1 , "Host connections need to be >= 1 ");
        this.needsReconnect |= this.maxHostConnections != maxHostConnections;
        this.maxHostConnections = maxHostConnections;
    }

    public boolean isValid()
    {
        return StringUtils.isNotBlank(apiUrl) &&
            StringUtils.isNotBlank( user ) &&
            StringUtils.isNotBlank(password);
    }

    public void setCredentials(String user,String password)
    {
        Validate.notBlank( user, "user must not be null or blank");
        Validate.notBlank( password, "password must not be null or blank");
        this.needsReconnect |= ! Objects.equals(this.user,user) || ! Objects.equals(this.password,password);
        this.user = user;
        this.password = password;
    }

    private MultiThreadedHttpConnectionManager conManager;

    public void connect() throws URIException
    {
        if ( isConnected() )
        {
            if ( ! needsReconnect() ) {
                return;
            }
            System.out.println("Disconnecting, configuration has changed");
            disconnect();
        }

        // host configuration
        final HostConfiguration hostConfiguration = new HostConfiguration();
        final URI uri = new URI( apiUrl, true );
        hostConfiguration.setHost( uri );

        // connection manager params
        final HttpConnectionManagerParams params = new HttpConnectionManagerParams();
        params.setMaxConnectionsPerHost( hostConfiguration, maxHostConnections );

        // connection manager
        conManager = new MultiThreadedHttpConnectionManager();
        conManager.setParams( params );

        // http client
        HttpClient client = new HttpClient(conManager);
        client.setHostConfiguration( hostConfiguration );

        client.getParams().setAuthenticationPreemptive( true );
        final Credentials credentials = new UsernamePasswordCredentials(user, password);
        int port = uri.getPort();
        if ( port == -1 ) {
            port = "https".equalsIgnoreCase( uri.getScheme() ) ? 443 : 80;
        }
        client.getState().setCredentials(new AuthScope( uri.getHost(), port, AuthScope.ANY_REALM), credentials);

        this.client = client;
    }

    public void disconnect()
    {
        client = null;
        if ( conManager != null )
        {
            try
            {
                conManager.shutdown();
            } finally {
                conManager = null;
            }
        }
    }

    public boolean isConnected()
    {
        return client != null;
    }

    public boolean needsReconnect()
    {
        return needsReconnect;
    }

    public List<Repository> getRepositories()
    {
        final Object[] result = rest( "/repositories", ArtifactoryClient::parseJSONArray );

        final List<Repository> resultList = new ArrayList<>();
        for ( Object obj : result )
        {
            final Map<String,Object> map = (Map<String, Object>) obj;
            System.out.println("Repository: "+map);
            var repoType = (String) map.get("type");
            var repoId = (String) map.get("key");
            resultList.add( new Repository( repoId, repoType ) );
        }
        return resultList;
    }

    public List<Item> getChildren(String repoId,String path)
    {
        final Map<String, Object> result = rest( "/storage/"+repoId+path, ArtifactoryClient::parseJSONMap );

        final List<Map<String,Object>> children = (List<Map<String, Object>>) result.get( "children" );
        List<Item> items = new ArrayList<>();
        for (Map<String, Object> entry : children )
        {
            final String uri = (String) entry.get("uri");
            final Boolean isFolder = (Boolean) entry.get("folder");
            final Item item;
            if ( isFolder != null && isFolder ) {
                // folder
                item = new Item(uri,true);
            } else {
                // item
                item = new Item( uri, false );
                final Map<String, Object> props = rest( "/storage/"+repoId+path+uri, ArtifactoryClient::parseJSONMap );
                item.sizeInBytes = Long.parseLong( (String) props.get("size") );
                item.lastUpdated = date( (String) props.get("lastUpdated"));
            }
            items.add( item );
        }
        return items;
    }

    private static Object[] parseJSONArray(InputStream stream)
    {
        try
        {
            return MAPPER.readValue(stream, TYPE_REF2);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Map<String,Object> parseJSONMap(InputStream stream)
    {
        try
        {
            return MAPPER.readValue(stream, TYPE_REF);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
    }

    private <T> T rest(String url, Function<InputStream, T> func)
    {
        try
        {
            // execute method
            connect();
            final HttpMethod httpMethod = new GetMethod( API_URL + url );
            client.executeMethod( httpMethod );
            if ( httpMethod.getStatusCode() != 200 )
            {
                throw new RuntimeException( "Server returned " + httpMethod.getStatusCode() + " (" + httpMethod.getStatusText() + ")" );
            }
            return func.apply( httpMethod.getResponseBodyAsStream() );
        }
        catch(IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ZonedDateTime date(String s)
    {
        s = s.replace( "+02:00" , "+0200").replace( "+01:00", "+0100");
        return ZonedDateTime.parse( s, JSON_DATE_FORMAT );
    }
}
