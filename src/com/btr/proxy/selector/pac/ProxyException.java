package com.btr.proxy.selector.pac;

/*****************************************************************************
 * Indicates an exception in the proxy framework.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/

public class ProxyException extends Exception {

	private static final long serialVersionUID = 1L;

	/*************************************************************************
	 * Constructor
	 ************************************************************************/

	public ProxyException() {
		super();
	}

	/*************************************************************************
	 * Constructor
	 * 
	 * @param message
	 *            the error message
	 * @param cause
	 *            the causing exception for chaining exceptions.
	 ************************************************************************/

	public ProxyException(String message, Throwable cause) {
		super(message, cause);
	}

	/*************************************************************************
	 * Constructor
	 * 
	 * @param message
	 *            the error message
	 ************************************************************************/

	public ProxyException(String message) {
		super(message);
	}

	/*************************************************************************
	 * Constructor
	 * 
	 * @param cause
	 *            the causing exception for chaining exceptions.
	 ************************************************************************/

	public ProxyException(Throwable cause) {
		super(cause);
	}

}
