/**
 * Copyright 2014-2016 Three Crickets LLC and Restlet S.A.S.
 * <p>
 * The contents of this file are subject to the terms of the Apache 2.0 license:
 * http://www.opensource.org/licenses/apache-2.0
 * <p>
 * This code is a derivative of code that is copyright 2005-2014 Restlet S.A.S.,
 * available at: https://github.com/restlet/restlet-framework-java
 * <p>
 * Restlet is a registered trademark of Restlet S.A.S.
 */

package org.restlet.ext.jetty9.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.client.util.InputStreamResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.restlet.Request;
import org.restlet.Response;
import org.restlet.Uniform;
import org.restlet.data.Header;
import org.restlet.data.Protocol;
import org.restlet.data.Status;
import org.restlet.engine.adapter.ClientCall;
import org.restlet.engine.header.HeaderConstants;
import org.restlet.ext.jetty9.JettyHttpClientHelper;
import org.restlet.representation.Representation;
import org.restlet.util.Series;

/**
 * HTTP client connector call based on Jetty's HttpRequest class.
 * 
 * @author Jerome Louvel
 * @author Tal Liron
 */
public class JettyClientCall extends ClientCall
{
	/**
	 * Constructor.
	 * 
	 * @param helper
	 *        The parent HTTP client helper.
	 * @param method
	 *        The method name.
	 * @param requestUri
	 *        The request URI.
	 * @throws IOException
	 *         In case of an I/O error
	 */
	public JettyClientCall( JettyHttpClientHelper helper, final String method, final String requestUri ) throws IOException
	{
		super( helper, method, requestUri );
		clientHelper = helper;

		if( requestUri.startsWith( "http:" ) || requestUri.startsWith( "https:" ) )
		{
			httpRequest = (HttpRequest) helper.getHttpClient().newRequest( requestUri );
			httpRequest.method( method );

			setConfidential( httpRequest.getURI().getScheme().equalsIgnoreCase( Protocol.HTTPS.getSchemeName() ) );
		}
		else
		{
			throw new IllegalArgumentException( "Only HTTP or HTTPS resource URIs are allowed here" );
		}
	}

	/**
	 * Returns the HTTP request.
	 * 
	 * @return The HTTP request.
	 */
	public HttpRequest getHttpRequest()
	{
		return httpRequest;
	}

	/**
	 * Returns the HTTP response.
	 * 
	 * @return The HTTP response.
	 */
	public HttpResponse getHttpResponse()
	{
		return httpResponse;
	}

	/**
	 * Returns the input stream response listener.
	 * 
	 * @return The input stream response listener.
	 */
	public InputStreamResponseListener getInputStreamResponseListener()
	{
		return inputStreamResponseListener;
	}

	/**
	 * Returns the response reason phrase.
	 * 
	 * @return The response reason phrase.
	 */
	@Override
	public String getReasonPhrase()
	{
		final HttpResponse httpResponse = getHttpResponse();
		return httpResponse == null ? null : httpResponse.getReason();
	}

	public WritableByteChannel getRequestEntityChannel()
	{
		return null;
	}

	public OutputStream getRequestEntityStream()
	{
		return null;
	}

	public OutputStream getRequestHeadStream()
	{
		return null;
	}

	public ReadableByteChannel getResponseEntityChannel( long size )
	{
		return null;
	}

	public InputStream getResponseEntityStream( long size )
	{
		final InputStreamResponseListener inputStreamResponseListener = getInputStreamResponseListener();
		return inputStreamResponseListener == null ? null : inputStreamResponseListener.getInputStream();
	}

	/**
	 * Returns the modifiable list of response headers.
	 * 
	 * @return The modifiable list of response headers.
	 */
	@Override
	public Series<Header> getResponseHeaders()
	{
		final Series<Header> result = super.getResponseHeaders();

		if( !responseHeadersAdded )
		{
			final HttpResponse httpResponse = getHttpResponse();
			if( httpResponse != null )
			{
				final HttpFields headers = httpResponse.getHeaders();
				if( headers != null )
				{
					for( HttpField header : headers )
						result.add( header.getName(), header.getValue() );
				}
			}

			responseHeadersAdded = true;
		}

		return result;
	}

	/**
	 * Returns the response address.<br>
	 * Corresponds to the IP address of the responding server.
	 * 
	 * @return The response address.
	 */
	@Override
	public String getServerAddress()
	{
		return getHttpRequest().getURI().getHost();
	}

	/**
	 * Returns the response status code.
	 * 
	 * @return The response status code.
	 */
	@Override
	public int getStatusCode()
	{
		final HttpResponse httpResponse = getHttpResponse();
		return httpResponse == null ? null : httpResponse.getStatus();
	}

	/**
	 * Sends the request to the client. Commits the request line, headers and
	 * optional entity and send them over the network.
	 * 
	 * @param request
	 *        The high-level request.
	 * @return The result status.
	 */
	@Override
	public Status sendRequest( Request request )
	{
		Status result = null;

		try
		{
			final Representation entity = request.getEntity();

			// Request entity
			if( entity != null )
				httpRequest.content( new InputStreamContentProvider( entity.getStream() ) );

			// Set the request headers
			for( Header header : getRequestHeaders() )
			{
				final String name = header.getName();
				if( !name.equals( HeaderConstants.HEADER_CONTENT_LENGTH ) )
					httpRequest.header( name, header.getValue() );
			}

			// Ensure that the connection is active
			inputStreamResponseListener = new InputStreamResponseListener();
			httpRequest.send( inputStreamResponseListener );
			httpResponse = (HttpResponse) inputStreamResponseListener.get( clientHelper.getTimeout(), TimeUnit.MILLISECONDS );

			result = new Status( getStatusCode(), getReasonPhrase() );
		}
		catch( IOException e )
		{
			clientHelper.getLogger().log( Level.WARNING, "An error occurred while reading the request entity.", e );
			result = new Status( Status.CONNECTOR_ERROR_INTERNAL, e );

			// Release the connection
			getHttpRequest().abort( e );
		}
		catch( TimeoutException e )
		{
			clientHelper.getLogger().log( Level.WARNING, "The HTTP request timed out.", e );
			result = new Status( Status.CONNECTOR_ERROR_COMMUNICATION, e );

			// Release the connection
			getHttpRequest().abort( e );
		}
		catch( InterruptedException e )
		{
			clientHelper.getLogger().log( Level.WARNING, "The HTTP request thread was interrupted.", e );
			result = new Status( Status.CONNECTOR_ERROR_COMMUNICATION, e );

			// Release the connection
			getHttpRequest().abort( e );
		}
		catch( ExecutionException e )
		{
			clientHelper.getLogger().log( Level.WARNING, "An error occurred while processing the HTTP request.", e );
			result = new Status( Status.CONNECTOR_ERROR_COMMUNICATION, e );

			// Release the connection
			getHttpRequest().abort( e );
		}

		return result;
	}

	@Override
	public void sendRequest( Request request, Response response, Uniform callback ) throws Exception
	{
		sendRequest( request );

		final Uniform getOnSent = request.getOnSent();
		if( getOnSent != null )
			getOnSent.handle( request, response );

		if( callback != null )
			// Transmit to the callback, if any
			callback.handle( request, response );
	}

	/**
	 * The associated HTTP client.
	 */
	private final JettyHttpClientHelper clientHelper;

	/**
	 * The wrapped HTTP request.
	 */
	private final HttpRequest httpRequest;

	/**
	 * The wrapped input stream response listener.
	 */
	private volatile InputStreamResponseListener inputStreamResponseListener;

	/**
	 * The wrapped HTTP response.
	 */
	private volatile HttpResponse httpResponse;

	/**
	 * Indicates if the response headers were added.
	 */
	private volatile boolean responseHeadersAdded;
}
