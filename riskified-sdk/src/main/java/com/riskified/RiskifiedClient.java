package com.riskified;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.Properties;

import org.apache.http.*;
import org.apache.http.auth.*;
import org.apache.http.client.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;

import com.google.gson.Gson;
import com.riskified.models.*;
import com.riskified.validations.*;


/**
 * Riskified API Client
 * The client implements the API for Riskified as described in:
 * http://apiref.riskified.com/
 */
public class RiskifiedClient {
    private Validation validation = Validation.ALL;
    private Environment environment = Environment.SANDBOX;
    private String baseUrl;
    private String baseUrlSyncAnalyze;
    private String shopUrl;
    private SHA256Handler sha256Handler;
    private int requestTimeout = 10000;
    private int connectionTimeout = 5000;
    private String authKey;

    private String proxyUrl;
    private int proxyPort;
    private String proxyUsername;
    private String proxyPassword;
    private HttpClientContext context;

    /**
     * Riskified API client
     * use configuration file: "src/main/resources/riskified_sdk.properties"
     * uses the keys: shopUrl, authKey, environment, debugRiskifiedHostUrl
     * see full doc on GitHub
     * @throws RiskifiedError When there was a critical error, look at the exception to see more data
     */
    public RiskifiedClient() throws RiskifiedError {
        Properties properties = new Properties();
        try {
            properties.load(getClass().getClassLoader().getResourceAsStream("riskified_sdk.properties"));
        } catch (IOException e) {
            throw new RiskifiedError("There was an error reading the config file in: src/main/resources/riskified_sdk.properties");
        }

        String shopUrl = properties.getProperty("shopUrl");
        String authKey = properties.getProperty("authKey");
        String environmentType = properties.getProperty("environment");
        String validationType = properties.getProperty("validation");

        String proxyUrl = properties.getProperty("proxyUrl");
        String proxyPort = properties.getProperty("proxyPort");
        String proxyUserName = properties.getProperty("proxyUsername");
        String proxyPassword = properties.getProperty("proxyPassword");

        if (validationType.equals("NONE")) {
            validation = Validation.NONE;
        } else if (validationType.equals("IGNORE_MISSING")) {
            validation = Validation.IGNORE_MISSING;
        } else if(validationType.equals("ALL")) {
        	validation = Validation.ALL;
        }

        if (environmentType.equals("DEBUG")) {
            environment = Environment.DEBUG;
        } else if (environmentType.equals("PRODUCTION")) {
            environment = Environment.PRODUCTION;
        } else if (environmentType.equals("SANDBOX")) {
        	environment = Environment.SANDBOX;
        }

        init(shopUrl, authKey, Utils.getBaseUrlFromEnvironment(environment), Utils.getBaseUrlSyncAnalzyeFromEnvironment(environment), validation);
        
        if (proxyUrl != null) {
            initProxy(proxyUrl, proxyPort, proxyUserName, proxyPassword);
        }
    }

    /**
     * Riskified API client
     * don't use config file
     * @param shopUrl The shop URL as registered in Riskified
     * @param authKey From the advance settings in Riskified web site
     * @param environment The Riskified environment (SANDBOX / PRODUCTION)
     * @throws RiskifiedError When there was a critical error, look at the exception to see more data
     */
    public RiskifiedClient(String shopUrl, String authKey, Environment environment) throws RiskifiedError {
        init(shopUrl, authKey, Utils.getBaseUrlFromEnvironment(environment), Utils.getBaseUrlSyncAnalzyeFromEnvironment(environment), Validation.ALL);
    }

    /**
     * Riskified API client (with proxy)
     * don't use config file
     * @param shopUrl The shop URL as registered in Riskified
     * @param authKey From the advance settings in Riskified web site
     * @param environment The Riskified environment (SANDBOX / PRODUCTION)
     * @param proxyClientDetails proxy details
     * @throws RiskifiedError When there was a critical error, look at the exception to see more data
     */
    public RiskifiedClient(String shopUrl, String authKey, Environment environment, ProxyClientDetails proxyClientDetails) throws RiskifiedError {
        init(shopUrl, authKey, Utils.getBaseUrlFromEnvironment(environment), Utils.getBaseUrlSyncAnalzyeFromEnvironment(environment), Validation.ALL);
        initProxy(proxyClientDetails);
    }

    /**
     * Riskified API client
     * don't use config file
     * @param shopUrl The shop URL as registered in Riskified
     * @param authKey From the advance settings in Riskified web site
     * @param environment The Riskified environment (SANDBOX / PRODUCTION)
     * @param validation The sdk's validation strategy
     * @throws RiskifiedError When there was a critical error, look at the exception to see more data
     */
    public RiskifiedClient(String shopUrl, String authKey, Environment environment, Validation validation) throws RiskifiedError {
        init(shopUrl, authKey, Utils.getBaseUrlFromEnvironment(environment), Utils.getBaseUrlSyncAnalzyeFromEnvironment(environment), validation);
    }

    /**
     * Riskified API client (with proxy)
     * don't use config file
     * @param shopUrl The shop URL as registered in Riskified
     * @param authKey From the advance settings in Riskified web site
     * @param environment The Riskifed environment (SANDBOX / PRODUCTION)
     * @param validation The sdk's validation strategy
     * @throws RiskifiedError When there was a critical error, look at the exception to see more data
     */
    public RiskifiedClient(String shopUrl, String authKey, Environment environment, Validation validation, ProxyClientDetails proxyClientDetails) throws RiskifiedError {
        init(shopUrl, authKey, Utils.getBaseUrlFromEnvironment(environment), Utils.getBaseUrlSyncAnalzyeFromEnvironment(environment), validation);
        initProxy(proxyClientDetails);
    }

    private void init(String shopUrl, String authKey, String baseUrl, String baseUrlSyncAnalyze, Validation validationType) throws RiskifiedError {
        this.baseUrl = baseUrl;
        this.baseUrlSyncAnalyze = baseUrlSyncAnalyze;
        this.shopUrl = shopUrl;
        this.sha256Handler = new SHA256Handler(authKey);
        this.validation = validationType;
    }

    private void initProxy(String proxyUrl, String proxyPort, String proxyUsername, String proxyPassword) {
    	this.proxyUrl = proxyUrl;
    	this.proxyPort = Integer.parseInt(proxyPort);
    	this.proxyUsername = proxyUsername;
    	this.proxyPassword = proxyPassword;
    }

    private void initProxy(ProxyClientDetails proxyClientDetails) {
    	this.proxyUrl = proxyClientDetails.getProxyUrl();
    	this.proxyPort = proxyClientDetails.getProxyPort();
    	this.proxyUsername = proxyClientDetails.getProxyUsername();
    	this.proxyPassword = proxyClientDetails.getProxyPassword();
    }

    /**
     * Send a new checkout order to Riskified
     * @param order The checkout order to create (Checkout order has the same fields like Order but ALL fields are optional)
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response checkoutOrder(CheckoutOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/checkout_create";

        // Validation.ALL is not relevant when checkout.
        if(validation != validation.NONE) {
            validate(order, Validation.IGNORE_MISSING);
        }

        return postCheckoutOrder(new CheckoutOrderWrapper<CheckoutOrder>(order), url);
    }

    /**
     * Send a new checkout order to Riskified
     * @param order The checkout order to create (Checkout order has the same fields like Order but ALL fields are optional)
     * @param validation Determines what type of validation will take place
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response checkoutOrder(CheckoutOrder order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/checkout_create";
        validate(order, validation);
        return postCheckoutOrder(new CheckoutOrderWrapper<CheckoutOrder>(order), url);
    }

    /**
     * Mark a previously checkout order has been denied.
     * @param order The checkout denied order details, mark as denied and also can specify why it was denied.
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response checkoutDeniedOrder(CheckoutDeniedOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/checkout_denied";
        validate(order);
        return postCheckoutOrder(new CheckoutOrderWrapper<CheckoutDeniedOrder>(order), url);
    }

    /**
     * Mark a previously checkout order has been denied.
     * @param order The checkout denied order details, mark as denied and also can specify why it was denied.
     * @param validation Determines what type of validation will take place
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response checkoutDeniedOrder(CheckoutDeniedOrder order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/checkout_denied";
        validate(order, validation);
        return postCheckoutOrder(new CheckoutOrderWrapper<CheckoutDeniedOrder>(order), url);
    }

    /**
     * Send a new order to Riskified
     * Depending on your current plan, the newly created order might not be submitted automatically for review.
     * @param order An order to create
     * Any missing fields (such as BIN number or AVS result code) that are unavailable during the time of the request should be skipped or passed as null
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response createOrder(Order order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/create";
        validate(order);
        return postOrder(new OrderWrapper<Order>(order), url);
    }

    /**
     * Send a new order to Riskified
     * Depending on your current plan, the newly created order might not be submitted automatically for review.
     * @param order An order to create
     * @param validation Determines what type of validation will take place
     * Any missing fields (such as BIN number or AVS result code) that are unavailable during the time of the request should be skipped or passed as null
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response createOrder(Order order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/create";
        validate(order, validation);
        return postOrder(new OrderWrapper<Order>(order), url);
    }

    /**
     * Submit a new or existing order to Riskified for review
     * Forces the order to be submitted for review, regardless of your current plan.
     * @param order An order to submit for review.
     * Any missing fields (such as BIN number or AVS result code) that are unavailable during the time of the request should be skipped or passed as null.
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response submitOrder(Order order) throws IOException, FieldBadFormatException {
        return submitOrder(order, validation);
    }

    /**
     * Submit a new or existing order to Riskified for review
     * Forces the order to be submitted for review, regardless of your current plan.
     * @param order An order to submit for review.
     * Any missing fields (such as BIN number or AVS result code) that are unavailable during the time of the request should be skipped or passed as null.
     * @param validation Determines what type of validation will take place
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response submitOrder(Order order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/submit";
        validate(order, validation);
        return postOrder(new OrderWrapper<Order>(order), url);
    }

    /**
     * Update details of an existing order.
     * Orders are differentiated by their id field. To update an existing order, include its id and any up-to-date data.
     * @param order A (possibly incomplete) order to update
     * The order must have an id field referencing an existing order and at least one additional field to update.
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response updateOrder(Order order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/update";

        // Validation.ALL is not relevant when updating.
        if(validation != validation.NONE) {
            validate(order, Validation.IGNORE_MISSING);
        }

        return postOrder(new OrderWrapper<Order>(order), url);
    }

    /**
     * Update details of an existing order.
     * Orders are differentiated by their id field. To update an existing order, include its id and any up-to-date data.
     * @param order A (possibly incomplete) order to update
     * The order must have an id field referencing an existing order and at least one additional field to update.
     * @param validation Determines what type of validation will take place
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response updateOrder(Order order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/update";
        validate(order, validation);
        return postOrder(new OrderWrapper<Order>(order), url);
    }

    /**
     * Mark a previously submitted order as cancelled.
     * If the order has not yet been reviewed, it is excluded from future review.
     * If the order has already been reviewed and approved, canceling it will also trigger a full refund on any associated charges.
     * An order can only be cancelled during a relatively short time window after its creation.
     * @param order The order to cancel
     * @see CancelOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response cancelOrder(CancelOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/cancel";
        validate(order);
        return postOrder(new OrderWrapper<CancelOrder>(order), url);
    }

    /**
     * Mark a previously submitted order as cancelled.
     * If the order has not yet been reviewed, it is excluded from future review.
     * If the order has already been reviewed and approved, canceling it will also trigger a full refund on any associated charges.
     * An order can only be cancelled during a relatively short time window after its creation.
     * @param order The order to cancel
     * @param validation Determines what type of validation will take place
     * @see CancelOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response cancelOrder(CancelOrder order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/cancel";
        validate(order, validation);
        return postOrder(new OrderWrapper<CancelOrder>(order), url);
    }

    /**
     * Issue a partial refund for an existing order.
     * Any associated charges will be updated to reflect the new order total amount.
     * @param order The refund Order
     * @see RefundOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response refundOrder(RefundOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/refund";
        validate(order);
        return postOrder(new OrderWrapper<RefundOrder>(order), url);
    }

    /**
     * Issue a partial refund for an existing order.
     * Any associated charges will be updated to reflect the new order total amount.
     * @param order The refund Order
     * @param validation Determines what type of validation will take place
     * @see RefundOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response refundOrder(RefundOrder order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/refund";
        validate(order, validation);
        return postOrder(new OrderWrapper<RefundOrder>(order), url);
    }

    /**
     * Mark a previously submitted order that is was fulfilled.
     * @param order The fulfillment order details
     * @see FulfillmentOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response fulfillOrder(FulfillmentOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/fulfill";
        validate(order);
        return postOrder(new OrderWrapper<FulfillmentOrder>(order), url);
    }

    /**
     * Mark a previously submitted order that is was fulfilled.
     * @param order The fulfillment order details
     * @param validation Determines what type of validation will take place
     * @see FulfillmentOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response fulfillOrder(FulfillmentOrder order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/fulfill";
        validate(order, validation);
        return postOrder(new OrderWrapper<FulfillmentOrder>(order), url);
    }

    /**
     * Set the decision made for order that was not submitted.
     * @param order The decision order details
     * @see DecisionOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response decisionOrder(DecisionOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/decision";
        validate(order);
        return postOrder(new OrderWrapper<DecisionOrder>(order), url);
    }

    /**
     * Set the decision made for order that was not submitted.
     * @param order The decision order details
     * @param validation Determines what type of validation will take place
     * @see DecisionOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response decisionOrder(DecisionOrder order, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/decision";
        validate(order, validation);
        return postOrder(new OrderWrapper<DecisionOrder>(order), url);
    }

    /**
     * Send & analyze a new order to Riskified
     * Analyzes the order synchronicly, the returned status is Riskified's analysis review result.
     * @param order An order to create & analyze
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response analyzeOrder(Order order) throws IOException, FieldBadFormatException {
        String url = baseUrlSyncAnalyze + "/api/decide";
        validate(order);
        return postOrder(new OrderWrapper<Order>(order), url);
    }
    
    /**
     * The chargeback API will allow merchants to request a fraud-related chargeback reimbursement. 
     * The submitted request will be processed within 48 hours.
     * Eligible requests will trigger an automatic credit refund by Riskified.
     * An eligible chargeback reimbursement request must match the details provided originally within the order JSON
     * and contain a fraudulent chargeback reason code. For tangible goods,
     * Riskified uses the tracking number provided in the fulfillment parameter to ensure the parcel was delivered
     * to the address provided within the order JSON. Riskified reserves the right to request additional documentation
     * pertaining to submitted chargebacks as part of the eligibility review process.
     * @param order The order to mark as chargeback
     * @see ChargebackOrder
     * @see Response
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response chargebackOrder(ChargebackOrder order) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/chargeback";
        validate(order);
        return postOrder(new OrderWrapper<ChargebackOrder>(order), url);
    }

    /**
     * Send an array (batch) of existing/historical orders to Riskified.
     * Use the decision field to provide information regarding each order status.
     *
     * Orders sent will be used to build analysis models to better analyze newly received orders.
     *
     * @param orders A list of historical orders to send
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response historicalOrders(ArrayOrders orders) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/historical";
        validate(orders);
        return postOrder(orders, url);
    }

    /**
     * Send an array (batch) of existing/historical orders to Riskified.
     * Use the financial_status field to provide information regarding each order status:
     * * 'approved' - approved orders
     * * 'declined-fraud' - declined orders (refunded or voided) as suspected fraud
     * * 'declined' - declined orders (refunded or voided) without connection to fraud
     * * 'chargeback' - orders that received a chargeback
     *
     * Orders sent will be used to build analysis models to better analyze newly received orders.
     *
     * @param orders A list of historical orders to send
     * @param validation Determines what type of validation will take place
     * @return Response object, including the status from Riskified server
     * @throws ClientProtocolException in case of a problem or the connection was aborted
     * @throws IOException in case of an http protocol error
     * @throws HttpResponseException The server respond status wasn't 200
     * @throws FieldBadFormatException
     */
    public Response historicalOrders(ArrayOrders orders, Validation validation) throws IOException, FieldBadFormatException {
        String url = baseUrl + "/api/historical";
        validate(orders, validation);
        return postOrder(orders, url);
    }

    private Response postCheckoutOrder(Object data, String url) throws IOException, FieldBadFormatException {
        HttpPost request = createPostRequest(url);
        addDataToRequest(data, request);
        HttpResponse response;
        HttpClient client = constructHttpClient();
        response = executeClient(client, request);
        String postBody = EntityUtils.toString(response.getEntity(), "UTF-8");
        int status = response.getStatusLine().getStatusCode();
        Response responseObject = getCheckoutResponseObject(postBody);
        switch (status) {
            case 200:
                return responseObject;
            case 400:
                throw new HttpResponseException(status, responseObject.getError().getMessage());
            case 401:
                throw new HttpResponseException(status, responseObject.getError().getMessage());
            case 404:
                throw new HttpResponseException(status, responseObject.getError().getMessage());
            case 504:
                throw new HttpResponseException(status, "Temporary error, please retry");
            default:
                throw new HttpResponseException(500, "Contact Riskified support");
        }
    }

	private HttpClient constructHttpClient() {
		RequestConfig.Builder requestBuilder = RequestConfig.custom()
				.setConnectTimeout(connectionTimeout)
				.setConnectionRequestTimeout(requestTimeout);
		HttpClientBuilder builder = HttpClientBuilder.create();
		builder.setDefaultRequestConfig(requestBuilder.build());

		if (this.proxyUrl != null) {
			setProxyWithAuth(builder);
		}

		return builder.build();
	}

	private HttpResponse executeClient(HttpClient client, HttpPost request)
			throws IOException {
		HttpResponse response;

		if (context != null) {
			response = client.execute(request, context);
		} else {
			response = client.execute(request);
		}

		return response;
	}

    private CredentialsProvider getHttpProxyCredentials() {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(new HttpHost(this.proxyUrl, this.proxyPort)),
                new UsernamePasswordCredentials(this.proxyUsername, this.proxyPassword));
       return credsProvider;
    }

	private void setProxyWithAuth(HttpClientBuilder builder) {
		builder.setProxy(new HttpHost(proxyUrl, proxyPort));
		builder.setDefaultCredentialsProvider(getHttpProxyCredentials());
		builder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());

		if (this.context == null) {
			try {
				setProxyContext();
			} catch (MalformedChallengeException e) {
				System.out.println("Error: failed to process challenge for proxy");
			}
		}
	}

	private void setProxyContext() throws MalformedChallengeException {
		BasicScheme proxyAuth = new BasicScheme();
		proxyAuth.processChallenge(new BasicHeader(AUTH.PROXY_AUTH,
				"BASIC realm=default"));
		BasicAuthCache authCache = new BasicAuthCache();
		authCache.put(new HttpHost(this.proxyUrl, this.proxyPort), proxyAuth);

		HttpClientContext context = HttpClientContext.create();
		context.setAuthCache(authCache);
		context.setCredentialsProvider(getHttpProxyCredentials());

		this.context = context;
	}

    private Response postOrder(Object data, String url) throws IOException {
        HttpPost request = createPostRequest(url);
        addDataToRequest(data, request);
        HttpResponse response;
        HttpClient client = constructHttpClient();
        response = executeClient(client, request);
        String postBody = EntityUtils.toString(response.getEntity());
        int status = response.getStatusLine().getStatusCode();
        Response responseObject = getResponseObject(postBody);
        switch (status) {
	        case 200:
	            return responseObject;
	        case 400:
	            throw new HttpResponseException(status, responseObject.getError().getMessage());
	        case 401:
	            throw new HttpResponseException(status, responseObject.getError().getMessage());
	        case 404:
	            throw new HttpResponseException(status, responseObject.getError().getMessage());
	        case 504:
	            throw new HttpResponseException(status, "Temporary error, please retry");
	        default:
	            throw new HttpResponseException(500, "Contact Riskified support");
	    }
    }

    private Response getResponseObject(String postBody) throws IOException {
        Gson gson = new Gson();
        Response res = gson.fromJson(postBody, Response.class);
        return res;
    }

    private CheckoutResponse getCheckoutResponseObject(String postBody) throws IOException {
        Gson gson = new Gson();
        CheckoutResponse res = gson.fromJson(postBody, CheckoutResponse.class);
        res.setOrder(res.getCheckout());
        return res;
    }

    private void addDataToRequest(Object data, HttpPost postRequest) throws IllegalStateException, UnsupportedEncodingException {
        String jsonData = JSONFormmater.toJson(data);

    	String hmac = sha256Handler.createSHA256(jsonData);
        postRequest.setHeader("X_RISKIFIED_HMAC_SHA256", hmac);

        StringEntity input;
        input = new StringEntity(jsonData, Charset.forName("UTF-8"));
		input.setContentType("application/json");
		postRequest.setEntity(input);
    }

    private HttpPost createPostRequest(String url) {
        HttpPost postRequest = new HttpPost(url);
        postRequest.setHeader(HttpHeaders.ACCEPT, "application/vnd.riskified.com; version=2");
        postRequest.setHeader("X_RISKIFIED_SHOP_DOMAIN", shopUrl);
        postRequest.setHeader("User-Agent","riskified_java_sdk/1.0.7"); // TODO: take the version automatically

        return postRequest;
    }

    private void validate(IValidated objToValidated) throws FieldBadFormatException {
        if (this.validation != Validation.NONE) {
            objToValidated.validate(this.validation);
        }
    }

    private void validate(IValidated objToValidated, Validation validationType) throws FieldBadFormatException {
        if (validationType != Validation.NONE) {
            objToValidated.validate(validationType);
        }
    }

    public String getShopUrl() {
        return shopUrl;
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getAuthKey() {
        return authKey;
    }

    /**
     * Change the Riskified server url
     * You shouldn't use this regular
     * @param url the new server url
     */
    public void setBaseUrl(String url) {
        this.baseUrl = url;
    }

    public Validation getValidation() {
        return validation;
    }

    public void setValidation(Validation validation) {
        this.validation = validation;
    }

    public static class RiskifiedClientBuilder {
        private String shopUrl;
        private String authKey;
        private Environment environment;
        private Integer requestTimeout;
        private Integer connectionTimeout;
        private Validation validation;

        /**
         * Required arguments to build a RiskifiedClient
         * @param shopUrl
         * @param authKey
         * @param environment
         */
        public RiskifiedClientBuilder(String shopUrl, String authKey, Environment environment) {
            this.shopUrl = shopUrl;
            this.authKey = authKey;
            this.environment = environment;
        }

        public RiskifiedClientBuilder setRequestTimeout(Integer requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public RiskifiedClientBuilder setConnectionTimeout(Integer connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public RiskifiedClientBuilder setValidation(Validation validation) {
            this.validation = validation;
            return this;
        }

        public RiskifiedClient build() throws RiskifiedError {
            return new RiskifiedClient(this);
        }
    }

    public RiskifiedClient(RiskifiedClientBuilder riskifiedClientBuilder) throws RiskifiedError {
        this.shopUrl = riskifiedClientBuilder.shopUrl;
        this.authKey = riskifiedClientBuilder.authKey;
        this.environment = riskifiedClientBuilder.environment;

        if (riskifiedClientBuilder.validation != null) {
            this.validation = riskifiedClientBuilder.validation;
        }

        if (riskifiedClientBuilder.requestTimeout != null) {
            this.requestTimeout = riskifiedClientBuilder.requestTimeout;
        }

        if (riskifiedClientBuilder.connectionTimeout != null) {
            this.connectionTimeout = riskifiedClientBuilder.connectionTimeout;
        }

        this.sha256Handler = new SHA256Handler(authKey);
        this.baseUrl = Utils.getBaseUrlFromEnvironment(environment);
    }
}
