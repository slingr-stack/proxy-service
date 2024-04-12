package io.slingr.services.proxy;

import io.slingr.services.Service;
import io.slingr.services.exceptions.ServiceException;
import io.slingr.services.exceptions.ErrorCode;
import io.slingr.services.framework.annotations.*;
import io.slingr.services.services.AppLogs;
import io.slingr.services.services.datastores.DataStore;
import io.slingr.services.services.datastores.DataStoreResponse;
import io.slingr.services.services.exchange.ApiUri;
import io.slingr.services.services.exchange.Parameter;
import io.slingr.services.services.logs.AppLogLevel;
import io.slingr.services.services.rest.DownloadedFile;
import io.slingr.services.services.rest.RestClient;
import io.slingr.services.services.rest.RestClientBuilder;
import io.slingr.services.services.rest.RestMethod;
import io.slingr.services.utils.FilesUtils;
import io.slingr.services.utils.Json;
import io.slingr.services.utils.Strings;
import io.slingr.services.ws.exchange.FunctionRequest;
import io.slingr.services.ws.exchange.UploadedFile;
import io.slingr.services.ws.exchange.WebServiceRequest;
import io.slingr.services.ws.exchange.WebServiceResponse;
import io.slingr.services.framework.annotations.ApplicationLogger;
import io.slingr.services.framework.annotations.SlingrService;
import org.apache.commons.lang.StringUtils;
import org.apache.http.entity.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service used as a proxy to services on the developer environment
 * <p>
 * Created by agreggio on 23/10/23.
 */
@SuppressWarnings("unchecked")
@SlingrService(name = "proxy")
public class Proxy extends Service {
    private static final Logger logger = LoggerFactory.getLogger(Proxy.class);

    private static final String DATA_STORE_NAME = "__ds_name__";
    private static final String DATA_STORE_NEW_ID = "__ds_id__";
    private static final String DATA_STORE_ID = "_id";
    private static final String CONFIGURATION_HELP_URL_VALUE = "/services_proxy.html#configuration";

    // Service services uris
    private static final String VAR_KEY = "key";
    private static final String VAR_DATA_STORE = "dataStore";
    private static final String VAR_DOCUMENT_ID = "documentId";
    private static final String VAR_FILE_ID = "fileId";
    private static final String EB_URL_PREFIX = "/api";
    private static final String URL_CONFIGURATION =     EB_URL_PREFIX + ApiUri.EB_URL_CONFIGURATION;
    private static final String URL_ASYNC_EVENT =       EB_URL_PREFIX + ApiUri.EB_URL_ASYNC_EVENT;
    private static final String URL_SYNC_EVENT =        EB_URL_PREFIX + ApiUri.EB_URL_SYNC_EVENT;
    private static final String URL_APP_LOG =           EB_URL_PREFIX + ApiUri.EB_URL_APP_LOG;
    private static final String URL_FILE_UPLOAD =       EB_URL_PREFIX + ApiUri.EB_URL_FILE_UPLOAD;
    private static final String URL_LOCK =              EB_URL_PREFIX + ApiUri.EB_URL_SERVICES_PREFIX+ApiUri.EB_PART_LOCK+"/{"+VAR_KEY+"}";
    private static final String URL_FILE_DOWNLOAD =     EB_URL_PREFIX + ApiUri.EB_URL_SERVICES_PREFIX+ApiUri.EB_PART_FILE+"/{"+VAR_FILE_ID+"}";
    private static final String URL_FILE_METADATA =     EB_URL_PREFIX + ApiUri.EB_URL_SERVICES_PREFIX+ApiUri.EB_PART_FILE+"/{"+VAR_FILE_ID+"}/"+ApiUri.EB_PART_METADATA;
    private static final String URL_DATA_STORE =        EB_URL_PREFIX + ApiUri.EB_URL_SERVICES_PREFIX+ApiUri.EB_PART_DATA_STORE+"/{"+VAR_DATA_STORE+"}";
    private static final String URL_DATA_STORE_BY_ID =  EB_URL_PREFIX + ApiUri.EB_URL_SERVICES_PREFIX+ApiUri.EB_PART_DATA_STORE+"/{"+VAR_DATA_STORE+"}/{"+VAR_DOCUMENT_ID+"}";
    private static final String URL_DATA_STORE_COUNT =  EB_URL_PREFIX + ApiUri.EB_URL_SERVICES_PREFIX+ApiUri.EB_PART_DATA_STORE+"/{"+VAR_DATA_STORE+"}/"+ApiUri.EB_PART_COUNT;
    private static final String URL_CLEAR_CACHE =       EB_URL_PREFIX + ApiUri.EB_URL_CLEAR_CACHE;

    @ApplicationLogger
    private AppLogs appLogger;

    @ServiceProperty
    private String serviceUri;

    @ServiceProperty
    private String serviceToken;

    @ServiceDataStore(name = "ds")
    private DataStore dataStore;

    @Override
    public void webServicesConfigured() {
        // enable interceptors
        baseModule.enableConfiguratorInterceptor();
        baseModule.enableFunctionInterceptor();
        baseModule.enableWebServicesInterceptor();
    }

    @Override
    public void serviceStarted() {
        logger.info(String.format("Configured Proxy Service - Service URI [%s], Service Token [%s]", serviceUri, Strings.maskToken(serviceToken)));
    }

    @Override
    public Object functionInterceptor(FunctionRequest request) throws ServiceException {
        final String functionName = request.getFunctionName();
        logger.info(String.format("Function request [%s] - id [%s]", functionName, request.getFunctionId()));

        final Json jsonRequest = request.toJson().set(Parameter.FUNCTION_NAME, functionName);

        try {
            Object body = request.getParams();
            if(!(body instanceof Json || body instanceof Map || body instanceof List)){
                body = Json.map().set(Parameter.REQUEST_WRAPPED, body);
            }
            jsonRequest.set(Parameter.PARAMS, body);

            final Json response = postJsonFromService(jsonRequest);

            logger.info(String.format("Function response [%s] received - id [%s]", functionName, request.getFunctionId()));
            return response.contains(Parameter.DATA) ? response.json(Parameter.DATA) : Json.map();
        } catch (ServiceException ex){
            appLogger.error(String.format("Exception when try to execute function on Service: %s", ex));
            throw ex;
        } catch (Exception ex){
            appLogger.error(String.format("Exception when try to execute function on Service: %s", ex.getMessage()));
            throw ServiceException.permanent(ErrorCode.CLIENT, String.format("Exception when try to execute function on Service: %s", ex.getMessage()), ex);
        }
    }

    @Override
    public Json configurationInterceptor(Json configuration) throws ServiceException {
        try {
            // removes proxy configuration
            configuration.set(Parameter.METADATA_HELP_URL, CONFIGURATION_HELP_URL_VALUE)
                    .remove(Parameter.METADATA_PER_USER)
                    .remove(Parameter.METADATA_CONFIGURATION)
                    .remove(Parameter.METADATA_FUNCTIONS)
                    .remove(Parameter.METADATA_EVENTS)
                    .remove(Parameter.METADATA_USER_CONF)
                    .remove(Parameter.METADATA_USER_CONF_BUTTONS)
                    .remove(Parameter.METADATA_JS)
                    .remove(Parameter.METADATA_LISTENERS);

            // get configuration from the external Service
            final Json serviceConfiguration = getJsonFromService();
            if(serviceConfiguration != null) {
                logger.info("Properties received from Service");
                configuration.set(Parameter.METADATA_PER_USER, serviceConfiguration.is(Parameter.METADATA_PER_USER, false))
                        .setIfNotNull(Parameter.METADATA_CONFIGURATION, serviceConfiguration.json(Parameter.METADATA_CONFIGURATION))
                        .setIfNotNull(Parameter.METADATA_FUNCTIONS, serviceConfiguration.jsons(Parameter.METADATA_FUNCTIONS))
                        .setIfNotNull(Parameter.METADATA_EVENTS, serviceConfiguration.jsons(Parameter.METADATA_EVENTS))
                        .setIfNotEmpty(Parameter.METADATA_USER_CONF, serviceConfiguration.jsons(Parameter.METADATA_USER_CONF))
                        .setIfNotEmpty(Parameter.METADATA_USER_CONF_BUTTONS, serviceConfiguration.json(Parameter.METADATA_USER_CONF_BUTTONS))
                        .setIfNotEmpty(Parameter.METADATA_JS,serviceConfiguration.string(Parameter.METADATA_JS))
                        .setIfNotEmpty(Parameter.METADATA_LISTENERS, serviceConfiguration.string(Parameter.METADATA_LISTENERS));
            }
        } catch (ServiceException ex){
            appLogger.error(String.format("Exception when try to request configuration from Service: %s", ex.getMessage()));
            logger.warn(String.format("Exception when try to request configuration from Service: %s", ex));
        } catch (Exception ex){
            appLogger.error(String.format("Exception when try to request configuration from Service: %s", ex.getMessage()));
            logger.warn(String.format("Exception when try to request configuration from Service: %s", ex.getMessage()), ex);
        }
        return configuration;
    }

    private Json getJsonFromService() {
        return RestClient.builder(this.serviceUri)
                .header(Parameter.TOKEN, this.serviceToken)
                .path(ApiUri.URL_CONFIGURATION)
                .get();
    }

    private Json postJsonFromService(final Json content) {
        return RestClient.builder(this.serviceUri)
                .header(Parameter.TOKEN, this.serviceToken)
                .path(ApiUri.URL_FUNCTION)
                .post(content);
    }

    @Override
    public Object webServicesInterceptor(WebServiceRequest request) throws ServiceException {
        if(StringUtils.isBlank(this.serviceUri)){
            return null;
        }

        String path = request.getPath();
        if(StringUtils.isBlank(path)){
            path = "/";
        }
        path = path.trim();

        final String queryString = Strings.convertToQueryString(request.getParameters());

        final Object body = request.getBody();
        final String bodyLog = request.getMethod() == RestMethod.POST || request.getMethod() == RestMethod.PUT || request.getMethod() == RestMethod.PATCH ?
                String.format(" - body [%s]", body != null ? body : "-") : "";
        logger.info(String.format("Generic web service request [%s %s%s]%s", request.getMethod().toString(), path, StringUtils.isNotBlank(queryString) ? String.format("?%s", queryString) : "", bodyLog));

        final RestClientBuilder client = RestClient
                .builder(this.serviceUri)
                .path(path);

        request.getHeaders().forEachMap((key, value) -> {
            if(!Parameter.CONTENT_LENGTH.equalsIgnoreCase(key) && !Parameter.HOST.equalsIgnoreCase(key)) {
                client.header(key, value);
            }
        });
        request.getParameters().forEachMapString(client::parameter);

        final Json serviceResponse = switch (request.getMethod()) {
            default -> client.get(true);
            case POST -> client.post(body, true);
            case PUT -> client.put(body, true);
            case DELETE -> client.delete(true);
            case OPTIONS -> client.options(true);
            case HEAD -> client.head(true);
            case PATCH -> client.patch(body, true);
        };

        final WebServiceResponse response;
        if(serviceResponse == null){
            response = new WebServiceResponse(String.format("Invalid response to [%s] method: no response", request.getMethod()));
            response.setHttpCode(500);
        } else if(!serviceResponse.contains("body")){
            response = new WebServiceResponse(String.format("Invalid response to [%s] method: %s", request.getMethod(), serviceResponse));
            response.setHttpCode(500);
        } else {
            if(serviceResponse.object("body") instanceof LinkedHashMap
                    && serviceResponse.contains("headers")
                    && serviceResponse.json("headers")!= null
                    && serviceResponse.json("headers").isNotEmpty()
                    && serviceResponse.json("headers").string("Content-Type") != null
                    && serviceResponse.json("headers").string("Content-Type").startsWith(ContentType.APPLICATION_JSON.getMimeType())
            ){
                logger.info(String.format("Body response to [%s] method fixed to Json", request.getMethod()));
                Json jsonBody = Json.fromMap((LinkedHashMap<String, ?>) serviceResponse.object("body"));
                response = new WebServiceResponse(jsonBody);
            }else{
                response = new WebServiceResponse(serviceResponse.object("body"));
            }

            if(serviceResponse.contains("status")){
                try {
                    response.setHttpCode(serviceResponse.integer("status"));
                } catch (Exception ex){
                    logger.warn(String.format("Exception on received status code [%s] - code 200 is returned: %s", serviceResponse.object("status"), ex.getMessage()), ex);
                }
            }
            if(serviceResponse.contains("headers")){
                try {
                    final Json hd = serviceResponse.json("headers");
                    if(hd != null && hd.isNotEmpty()){
                        for (String header : hd.keys()) {
                            if(!Parameter.CONTENT_LENGTH.equals(header) && !Parameter.HOST.equalsIgnoreCase(header)) {
                                response.setHeader(header, hd.string(header));
                            }
                        }
                    }
                } catch (Exception ex){
                    logger.warn(String.format("Exception on received headers [%s] - empty headers will be returned: %s", serviceResponse.object("headers"), ex.getMessage()), ex);
                }
            }
        }

        return response;
    }

    @ServiceWebService(path = URL_CONFIGURATION, methods = RestMethod.GET)
    public Json serviceConfiguration(WebServiceRequest request){
        logger.info("Properties request received");
        checkToken(request.getHeader(Parameter.TOKEN));

        return Json.map()
                .set(Parameter.CONFIGURATION_PROXY, true)
                .set(Parameter.CONFIGURATION_WEB_SERVICE_URI, properties().getWebServicesUri())
        ;
    }

    @ServiceWebService(path = URL_ASYNC_EVENT, methods = RestMethod.POST)
    public Json serviceAsyncEvent(WebServiceRequest request){
        logger.info("Event received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final Json event = request.getJsonBody();

        events().send(
                event.longInteger(Parameter.DATE),
                event.string(Parameter.EVENT_NAME),
                event.object(Parameter.DATA),
                event.string(Parameter.FROM_FUNCTION_ID),
                event.string(Parameter.USER_ID),
                event.string(Parameter.USER_EMAIL),
                0
        );
        logger.info("Event sent to application");
        return Json.map();
    }

    @ServiceWebService(path = URL_SYNC_EVENT, methods = RestMethod.POST)
    public Json serviceSyncEvent(WebServiceRequest request){
        logger.info("Event received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final Json event = request.getJsonBody();
        try {
            Object response = events().sendSync(
                    event.longInteger(Parameter.DATE),
                    event.string(Parameter.EVENT_NAME),
                    event.object(Parameter.DATA),
                    event.string(Parameter.FROM_FUNCTION_ID),
                    event.string(Parameter.USER_ID),
                    event.string(Parameter.USER_EMAIL),

                    0
            );
            logger.info(String.format("Sync event sent to application [%s]", response != null ? response.toString() : "-"));

            if (response == null) {
                response = Json.map();
            }

            Json jsonResponse = Json.fromObject(response, false, true);
            if (jsonResponse == null) {
                jsonResponse = Json.map().set(Parameter.SYNC_RESPONSE, response);
            }
            return jsonResponse;
        } catch (Exception ex){
            logger.warn(String.format("Exception when send sync event to application [%s]", ex));
            return Json.map().set(Parameter.SYNC_ERROR_RESPONSE, ex.getMessage());
        }
    }

    @ServiceWebService(path = URL_APP_LOG, methods = RestMethod.POST)
    public void serviceAppLog(WebServiceRequest request){
        logger.info("App log received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final Json appLog = request.getJsonBody();

        appLogs().sendAppLog(
                appLog.longInteger(Parameter.DATE),
                AppLogLevel.fromString(appLog.string(Parameter.APP_LOG_LEVEL)),
                appLog.string(Parameter.APP_LOG_MESSAGE),
                appLog.json(Parameter.APP_LOG_ADDITIONAL_INFO)
        );
        logger.info("App log sent to application");
    }

    @ServiceWebService(path = URL_FILE_METADATA, methods = RestMethod.GET)
    public Json serviceFileMetadata(WebServiceRequest request){
        logger.info("File - get file metadata");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String fileId = request.getPathVariable(VAR_FILE_ID);
        final Json response = files().metadata(fileId);

        logger.info(String.format("File - metadata [%s]", response.string("fileName")));
        return response;
    }

    @ServiceWebService(path = URL_FILE_DOWNLOAD, methods = RestMethod.GET)
    public InputStream serviceDownloadFile(WebServiceRequest request){
        logger.info("File - download file from app");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String fileId = request.getPathVariable(VAR_FILE_ID);

        final DownloadedFile file = files().download(fileId);
        if(file != null && file.getFile() != null) {
            final File tmp = FilesUtils.copyInputStreamToTemporaryFile(fileId, file.getFile(), true);
            if(tmp.exists()){
                try{
                    logger.info("File - input stream sent");
                    return new FileInputStream(tmp);
                } catch (Exception ex){
                    logger.warn(String.format("Exception when download file: %s", ex.getMessage()), ex);
                }
            }
        }
        logger.warn(String.format("File [%s] was not downloaded", fileId));
        return null;
    }

    @ServiceWebService(path = URL_FILE_UPLOAD, methods = RestMethod.POST)
    public Json serviceUploadFile(WebServiceRequest request){
        logger.info("File - upload file to app");
        checkToken(request.getHeader(Parameter.TOKEN));

        InputStream fileIs = null;
        String fileName = Parameter.FILE_UPLOAD_PARAMETER;
        String fileContentType = null;

        for (UploadedFile file : request.getFiles()) {
            if(file.getName().equals(Parameter.FILE_UPLOAD_PARAMETER)){
                fileIs = file.getFile();
                fileName = file.getFilename();

                if(StringUtils.isNotBlank(file.getContentType())){
                    fileContentType = file.getContentType();
                } else {
                    fileContentType = file.getHeaders().string(Parameter.CONTENT_TYPE.toLowerCase());
                }
            }
        }

        if(fileIs == null){
            Object body = request.getBody();
            if(body instanceof String) {
                try {
                    fileIs = new ByteArrayInputStream(((String) body).getBytes(StandardCharsets.ISO_8859_1));
                } catch (Exception ex) {
                    logger.warn(String.format("Exception when try to parse the file as stream: %s", ex.getMessage()), ex);
                }
            } else if(body instanceof InputStream){
                fileIs = (InputStream) body;
            }
        }

        Json response = Json.map();
        if(fileIs != null) {
            try {
                final File tmp = FilesUtils.copyInputStreamToTemporaryFile(fileName, fileIs, true);
                if (tmp.exists()) {
                    response = files().upload(fileName, new FileInputStream(tmp), fileContentType);
                } else {
                    logger.warn("Temporal file was not created");
                }
            } catch (Exception ex) {
                logger.warn(String.format("Exception when try to upload file to application: %s", ex.getMessage()), ex);
            }
        }

        if(response != null) {
            logger.info(String.format("File - file [%s]", response.string("fileId")));
        } else {
            logger.warn("Uploaded file can not be processed");
        }
        return response;
    }

    @ServiceWebService(path = URL_DATA_STORE, methods = RestMethod.POST)
    public Json serviceDataStoreSaveDocument(WebServiceRequest request){
        logger.info("Data store - save received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final Json document = request.getJsonBody();
        final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);

        return internalDataStoreSaveDocument("saved", dataStoreName, null, document);
    }

    @ServiceWebService(path = URL_DATA_STORE_BY_ID, methods = RestMethod.PUT)
    public Json serviceDataStoreUpdateDocument(WebServiceRequest request){
        logger.info("Data store - update received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final Json document = request.getJsonBody();
        final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);
        final String documentId = request.getPathVariable(VAR_DOCUMENT_ID);

        return internalDataStoreSaveDocument("updated", dataStoreName, documentId, document);
    }

    @ServiceWebService(path = URL_DATA_STORE_COUNT, methods = RestMethod.GET)
    public Json serviceDataStoreCountDocuments(WebServiceRequest request){
        logger.info("Data store - count");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);
        final Json parameters = request.getParameters();

        final DataStoreResponse response = internalDataStoreFindDocuments(dataStoreName, parameters);

        logger.info(String.format("Data store - count [%s]", response.getItems().size()));
        return Json.map()
                .set(Parameter.DATA_STORE_TOTAL, response.getItems().size());
    }

    @ServiceWebService(path = URL_DATA_STORE_BY_ID, methods = RestMethod.GET)
    public Json serviceDataStoreFindDocumentById(WebServiceRequest request){
        logger.info("Data store - find by id");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);
        final String documentId = request.getPathVariable(VAR_DOCUMENT_ID);

        return internalDataStoreFindDocumentById(dataStoreName, documentId, true);
    }

    @ServiceWebService(path = URL_DATA_STORE, methods = RestMethod.GET)
    public Json serviceDataStoreFindDocuments(WebServiceRequest request){
        logger.info("Data store - find");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);
        final Json parameters = request.getParameters();

        final DataStoreResponse response = internalDataStoreFindDocuments(dataStoreName, parameters);

        logger.info(String.format("Data store - found [%s]", response.getItems().size()));
        return Json.map()
                .set(Parameter.DATA_STORE_ITEMS, response.getItems())
                .set(Parameter.DATA_STORE_TOTAL, response.getTotal())
                .set(Parameter.PAGINATION_OFFSET, response.getOffset());
    }

    @ServiceWebService(path = URL_DATA_STORE_BY_ID, methods = RestMethod.DELETE)
    public Json serviceDataStoreRemoveDocumentById(WebServiceRequest request){
        logger.info("Data store - remove");
        checkToken(request.getHeader(Parameter.TOKEN));

        boolean removed = false;

        final String documentId = request.getPathVariable(VAR_DOCUMENT_ID);
        if(StringUtils.isNotBlank(documentId)) {
            final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);
            final Json oldDocument = internalDataStoreFindDocumentById(dataStoreName, documentId, false);
            if(oldDocument != null && !oldDocument.isEmpty()){
                final String oldId = oldDocument.string(DATA_STORE_ID);
                if(StringUtils.isNotBlank(oldId)){
                    removed = dataStore.removeById(oldId);
                }
            }
        }

        logger.info(String.format("Data store - removed [%s]", removed));
        return Json.map()
                .set(Parameter.DATA_STORE_RESULT, removed)
                .set(Parameter.DATA_STORE_TOTAL, removed ? 1 : 0);
    }

    @ServiceWebService(path = URL_DATA_STORE, methods = RestMethod.DELETE)
    public Json serviceDataStoreRemoveDocuments(WebServiceRequest request){
        logger.info("Data store - remove all");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String dataStoreName = request.getPathVariable(VAR_DATA_STORE);
        boolean removed = false;
        if(StringUtils.isNotBlank(dataStoreName)) {
            final Json parameters = request.getParameters();
            final Json filter = internalDataStoreFilter(dataStoreName, parameters);

            removed = dataStore.remove(filter);
        }

        logger.info(String.format("Data store - removed all [%s]", removed));
        return Json.map()
                .set(Parameter.DATA_STORE_RESULT, removed)
                .set(Parameter.DATA_STORE_TOTAL, removed ? 1 : 0);
    }

    @ServiceWebService(path = URL_LOCK, methods = RestMethod.POST)
    public Json serviceLockKey(WebServiceRequest request){
        logger.info("Lock key request received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String key = request.getPathVariable(VAR_KEY);
        boolean acquired = locks().lock(key);
        logger.info(String.format("Lock acquired [%s]: %s", key, acquired));

        return Json.map().set(Parameter.LOCK_ACQUIRED, acquired);
    }

    @ServiceWebService(path = URL_LOCK, methods = RestMethod.DELETE)
    public Json serviceUnlockKey(WebServiceRequest request){
        logger.info("Unlock key request received");
        checkToken(request.getHeader(Parameter.TOKEN));

        final String key = request.getPathVariable(VAR_KEY);
        boolean lockReleased = locks().unlock(key);
        logger.info(String.format("Lock released [%s]: %s", key, lockReleased));

        return Json.map().set(Parameter.LOCK_RELEASED, lockReleased);
    }

    @ServiceWebService(path = URL_CLEAR_CACHE, methods = RestMethod.PUT)
    public Json serviceClearCache(WebServiceRequest request){
        logger.info("Clear cache");
        checkToken(request.getHeader(Parameter.TOKEN));

        management().clearCache();

        return Json.map();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    // Helpers
    ///////////////////////////////////////////////////////////////////////////////////////////////

    /** Check the token of the request */
    private void checkToken(String token){
        if(StringUtils.isNotBlank(serviceToken) && !serviceToken.equals(token)){
            throw ServiceException.permanent(ErrorCode.API, "Invalid Service services token (check proxy configuration page)");
        }
    }

    private Json internalDataStoreSaveDocument(String label, String dataStoreName, String documentId, Json document){
        document.set(DATA_STORE_NAME, dataStoreName);

        String id = documentId;
        if(StringUtils.isBlank(id)) {
            id = document.string(DATA_STORE_ID);
            if (StringUtils.isBlank(id)) {
                id = Strings.randomUUIDString();
            }
        }
        document.set(DATA_STORE_NEW_ID, id)
                .remove(DATA_STORE_ID);

        final Json oldDocument = internalDataStoreFindDocumentById(dataStoreName, id, false);
        if(oldDocument != null && !oldDocument.isEmpty()){
            final String oldId = oldDocument.string(DATA_STORE_ID);
            if(StringUtils.isNotBlank(oldId)){
                document.set(DATA_STORE_ID, oldId);
            }
        }

        final Json response = dataStore.save(document);
        final String internalId = response.string(DATA_STORE_NEW_ID);
        logger.info(String.format("Data store - %s [%s], internal [%s]", label, id, internalId));

        document.set(DATA_STORE_ID, internalId)
                .remove(DATA_STORE_NAME)
                .remove(DATA_STORE_NEW_ID);
        return document;
    }

    private Json internalDataStoreFilter(String dataStoreName, Json parameters){
        final Json filter = Json.map();
        filter.set(DATA_STORE_NAME, dataStoreName);

        if(parameters != null && !parameters.isEmpty()){
            parameters.forEachMapString((key, value) -> {
                if(DATA_STORE_ID.equals(key)){
                    filter.set(DATA_STORE_NEW_ID, value);
                } else {
                    filter.set(key, value);
                }
            });
        }
        return filter;
    }

    private DataStoreResponse internalDataStoreFindDocuments(String dataStoreName, Json parameters){
        final Json filter = internalDataStoreFilter(dataStoreName, parameters);

        final DataStoreResponse response = dataStore.find(filter);
        final List<Json> items = response.getItems();
        items.forEach(document -> document
                .set(DATA_STORE_ID, document.string(DATA_STORE_NEW_ID))
                .remove(DATA_STORE_NEW_ID)
                .remove(DATA_STORE_NAME)
        );

        return new DataStoreResponse(items, response.getTotal(), response.getOffset());
    }

    private Json internalDataStoreFindDocumentById(String dataStoreName, String documentId, boolean convert){
        if(StringUtils.isBlank(dataStoreName) || StringUtils.isBlank(documentId)){
            return Json.map();
        }
        final Json filter = Json.map()
                .set(DATA_STORE_NAME, dataStoreName)
                .set(DATA_STORE_NEW_ID, documentId);

        final DataStoreResponse response = dataStore.find(filter);
        final List<Json> items = response.getItems();

        Json result = items.isEmpty() ? null : items.get(0);
        if(result == null){
            result = Json.map();
            logger.info(String.format("Data store - not found [%s]", documentId));
        } else {
            if(convert) {
                result = result.set(DATA_STORE_ID, result.string(DATA_STORE_NEW_ID))
                        .remove(DATA_STORE_NAME)
                        .remove(DATA_STORE_NEW_ID);
            }
            logger.info(String.format("Data store - found [%s]", documentId));
        }
        return result;
    }
}
