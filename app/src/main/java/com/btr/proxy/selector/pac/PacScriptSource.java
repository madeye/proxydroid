package com.btr.proxy.selector.pac;

import java.io.IOException;

/*****************************************************************************
 * An source to fetch the PAC script from.
 * 
 * @author Bernd Rosstauscher (proxyvole@rosstauscher.de) Copyright 2009
 ****************************************************************************/

public interface PacScriptSource {

	/*************************************************************************
	 * Gets the PAC script content as String.
	 * 
	 * @return a script.
	 * @throws IOException
	 *             on read error.
	 ************************************************************************/

	public String getScriptContent() throws IOException;

}