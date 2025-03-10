/*
 * Copyright (c) 2002-2013, Mairie de Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.releaser.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;

import fr.paris.lutece.plugins.releaser.util.CommandResult;
import fr.paris.lutece.plugins.releaser.util.ConstanteUtils;
import fr.paris.lutece.plugins.releaser.util.ReleaserUtils;
import fr.paris.lutece.plugins.releaser.util.maven.MavenGoals;
import fr.paris.lutece.plugins.releaser.util.maven.MavenUtils;
import fr.paris.lutece.plugins.releaser.util.svn.SvnUser;
import fr.paris.lutece.portal.service.spring.SpringContextService;
import fr.paris.lutece.portal.service.util.AppPropertiesService;

/**
 * 
 * MavenService : provides maven command launcher
 * 
 */
public class MavenService implements IMavenService
{
    // private static IMavenService _singleton;
    private Invoker _invoker;
    private static IMavenService _instance;
    

    private MavenService( )
    {
       
    }
    
    public static IMavenService getService()
    {
        if(_instance==null)
        {
            _instance=SpringContextService.getBean(ConstanteUtils.BEAN_MAVEN_SERVICE );
            _instance.init( );
        }
            
      return _instance;
        
    }

    public void init( )
    {
        _invoker = new DefaultInvoker( );
        _invoker.setMavenHome( new File( AppPropertiesService.getProperty( ConstanteUtils.PROPERTY_MAVEN_HOME_PATH ) ) );
        _invoker.setLocalRepositoryDirectory( new File( AppPropertiesService.getProperty( ConstanteUtils.PROPERTY_MAVEN_LOCAL_REPOSITORY ) ) );
        
    }

    /*
     * (non-Javadoc)
     * 
     * @see fr.paris.lutece.plugins.deployment.service.IMavenService#mvnSiteAssembly(java.lang.String, fr.paris.lutece.plugins.deployment.business.Environment,
     * fr.paris.lutece.plugins.deployment.business.MavenUser)
     */
    public void mvnSiteAssembly( String strSiteName, String strTagName, String strMavenProfile, SvnUser user, CommandResult commandResult )
    {
        String strSiteLocalBasePath = ReleaserUtils.getLocalSitePath( strSiteName );

        List<String> listGoals = MavenGoals.LUTECE_SITE_ASSEMBLY.asList( );
        List<String> listGoalsProfile = new ArrayList<String>( );
        listGoalsProfile.addAll( listGoals );
        listGoalsProfile.add( "-P " + strMavenProfile );
        listGoalsProfile.add( "-U" );
        // mvnExecute( strTagName, strSiteLocalBasePath, listGoalsProfile, commandResult );
    }

    /**
     * Transforme la liste en chaine, pour passer l'argument � la ligne de commande
     * 
     * @param goals
     * @return
     */


    // public static IMavenService getInstance()
    // {
    //
    // if(_singleton ==null)
    // {
    // _singleton=new MavenService();
    // }
    //
    // return _singleton;
    //
    // }
    //

    /**
     * Launches mvn cmd
     * 
     * @param strPluginName
     *            plugin name (ex: plugin-ods)
     * @param goals
     *            maven goals
     * @param strSVNBinPath
     *            svn bin path (ex: /home/svn/apps/subversion/bin)
     */
   
    private synchronized InvocationResult mvnExecute( String strPathPom, List<String> goals, CommandResult commandResult )
    {
        InvocationRequest request = new DefaultInvocationRequest( );
        request.setPomFile( new File( strPathPom ) );
        request.setGoals( goals );
        request.setShowErrors( true );
        request.setShellEnvironmentInherited( true );
        
        String strProxyHost=AppPropertiesService.getProperty( ConstanteUtils.PROPERTY_PROXY_HOST );
        String strProxyPort=AppPropertiesService.getProperty( ConstanteUtils.PROPERTY_PROXY_PORT );
         
        if(!StringUtils.isEmpty( strProxyHost ) && !StringUtils.isEmpty( strProxyPort ))
        {
            request.setMavenOpts( "-Dhttps.proxyHost="+strProxyHost+"  -Dhttps.proxyPort="+strProxyPort+" -Dhttp.proxyHost="+strProxyHost+"  -Dhttp.proxyPort="+strProxyPort +" -Dfile.encoding=UTF-8");
        }
        InvocationResult invocationResult = null;
        try
        {
            final StringBuffer sbLog = commandResult.getLog( );
            
            // logger
            _invoker.setOutputHandler( new InvocationOutputHandler( )
            {
                public void consumeLine( String strLine )
                {
                    sbLog.append( strLine + "\n" );
                }
            } );

            invocationResult = _invoker.execute( request );

            return invocationResult;

        }
        catch( Exception e )
        {

            ReleaserUtils.addTechnicalError( commandResult, commandResult.getLog( ).toString( ), e );
        }

        return invocationResult;
    }

    public String mvnReleasePerform( String strPathPom,String strUsername, String strPassword, CommandResult commandResult )
    {
        InvocationResult invocationResult = mvnExecute( strPathPom, MavenGoals.RELEASE_PERFORM.asList( ), commandResult );
        int nStatus = invocationResult.getExitCode( );

        if ( nStatus != 0 )
        {
            ReleaserUtils.addTechnicalError( commandResult, "Error during Release Perform exit code is: " + nStatus );
        }
        
        return "";
    }

    /**
     * 
     * mvnReleasePrepare
     * 
     * @param strBasePath
     *            chemin sur le disque pour l'acces au composant
     * @param strPluginName
     *            le nom du composant
     * @param strReleaseVersion
     *            la version a release
     * @param strTag
     *            le nom du tag
     * @param strDevelopmentVersion
     *            la prochaine version de developpement (avec -SNAPSHOT)
     * @return le thread
     */
    public String mvnReleasePrepare( String strPathPom, String strReleaseVersion, String strTag, String strDevelopmentVersion, String strUsername,
            String strPassword, CommandResult commandResult )
    {
        List<String> listGoals = MavenUtils.createReleasePrepare( strReleaseVersion, strTag, strDevelopmentVersion, strUsername, strPassword );

        InvocationResult invocationResult = mvnExecute( strPathPom, listGoals, commandResult );

        int nStatus = invocationResult.getExitCode( );

        if ( nStatus != 0 )
        {
            ReleaserUtils.addTechnicalError( commandResult, "Error during Release Prepare exit code is: " + nStatus );
        }
        
        return "";
    }

}
