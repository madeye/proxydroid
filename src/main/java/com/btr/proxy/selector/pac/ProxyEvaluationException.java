package com.btr.proxy.selector.pac;

/*****************************************************************************
 * Exception for PAC script errors.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/

public class ProxyEvaluationException extends ProxyException {

	private static final long serialVersionUID = 1L;

	/*************************************************************************
	 * Constructor
	 ************************************************************************/

	public ProxyEvaluationException() {
		super();
	}

	/*************************************************************************
	 * Constructor
	 * 
	 * @param message
	 *            the error message.
	 * @param cause
	 *            the causing exception for exception chaining.
	 ************************************************************************/

	public ProxyEvaluationException(String message, Throwable cause) {
		super(message, cause);
	}

	/*************************************************************************
	 * Constructor
	 * 
	 * @param message
	 *            the error message.
	 ************************************************************************/

	public ProxyEvaluationException(String message) {
		super(message);
	}

	/*************************************************************************
	 * Constructor
	 * 
	 * @param cause
	 *            the causing exception for exception chaining.
	 ************************************************************************/

	public ProxyEvaluationException(Throwable cause) {
		super(cause);
	}

}
