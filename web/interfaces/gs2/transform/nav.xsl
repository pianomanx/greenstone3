<?xml version="1.0" encoding="ISO-8859-1"?>
<xsl:stylesheet version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:java="http://xml.apache.org/xslt/java"
  xmlns:util="xalan://org.greenstone.gsdl3.util.XSLTUtil"
  extension-element-prefixes="java util"
  exclude-result-prefixes="java util">
  
  <!-- style includes global params interface_name, library_name -->
  <xsl:include href="style.xsl"/>

  <xsl:output method="html"/>  
  
  <xsl:template match="page">
    <html>
      <head>
	<title>
	  <xsl:call-template name="pageTitle"/><xsl:text> </xsl:text>
	</title>
	<xsl:call-template name="globalStyle"/>
	<base target="_top"/>
      </head>
      <body style="background-image: none;">
	<center>
	  <table width="537">
	    <tr><td>
		<xsl:apply-templates select="pageResponse"/>
	      </td></tr>
	  </table>
	</center>
      </body>
    </html>
  </xsl:template>

  <xsl:template name="pageTitle">
    <xsl:value-of select="/page/pageResponse/*/displayItem[@name='name']"/>
  </xsl:template>

  <xsl:template match="pageResponse">
    <xsl:variable name="collName" select="/page/pageRequest/paramList/param[@name='c']/@value"/>
    <div align="right">
      <xsl:call-template name="top-buttons">
	<xsl:with-param name="collName" select="$collName"/>
      </xsl:call-template>
    </div>
    <xsl:call-template name="navigationBar">
      <xsl:with-param name="collName" select="$collName"/>
    </xsl:call-template>      
  </xsl:template>

</xsl:stylesheet>
