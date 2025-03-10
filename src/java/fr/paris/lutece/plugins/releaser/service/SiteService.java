/*
 * Copyright (c) 2002-2017, Mairie de Paris
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


import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.servlet.http.HttpServletRequest;

import fr.paris.lutece.plugins.releaser.business.Component;
import fr.paris.lutece.plugins.releaser.business.Dependency;
import fr.paris.lutece.plugins.releaser.business.Site;
import fr.paris.lutece.plugins.releaser.business.SiteHome;
import fr.paris.lutece.plugins.releaser.business.WorkflowReleaseContext;
import fr.paris.lutece.plugins.releaser.util.ConstanteUtils;
import fr.paris.lutece.plugins.releaser.util.ReleaserUtils;
import fr.paris.lutece.plugins.releaser.util.pom.PomParser;
import fr.paris.lutece.plugins.releaser.util.svn.SvnSiteService;
import fr.paris.lutece.plugins.releaser.util.version.Version;
import fr.paris.lutece.plugins.releaser.util.version.VersionParsingException;
import fr.paris.lutece.portal.business.user.AdminUser;
import fr.paris.lutece.portal.service.datastore.DatastoreService;
import fr.paris.lutece.portal.service.i18n.I18nService;
import fr.paris.lutece.portal.service.util.AppLogService;
import fr.paris.lutece.util.httpaccess.HttpAccessException;

/**
 * SiteService
 */
public class SiteService
{
    private static final int NB_POOL_REMOTE_INFORMATION = 60;
    private static final String MESSAGE_AVOID_SNAPSHOT = "releaser.message.avoidSnapshot";
    private static final String MESSAGE_UPGRADE_SELECTED = "releaser.message.upgradeSelected";
    private static final String MESSAGE_TO_BE_RELEASED = "releaser.message.toBeReleased";
    private static final String MESSAGE_MORE_RECENT_VERSION_AVAILABLE = "releaser.message.moreRecentVersionAvailable";
    private static final String MESSAGE_AN_RELEASE_VERSION_ALREADY_EXIST = "releaser.message.releleaseVersionAlreadyExist";
    

    /**
     * Load a site from its id
     * 
     * @param nSiteId
     *            The site id
     * @return A site object
     */
    public static Site getSite( int nSiteId ,HttpServletRequest request,Locale locale )
    {
        Site site = SiteHome.findByPrimaryKey( nSiteId );
        String strPom = SvnSiteService.fetchPom( site.getScmUrl( ) + "/pom.xml" ,request,locale);
        if ( strPom != null )
        {
            PomParser parser = new PomParser( );
            parser.parse( site, strPom );
            initSite( site,request,locale );
        }
        return site;
    }

    
    private static void initSite( Site site,HttpServletRequest request,Locale locale )
    {
        // Find last release in the repository
        String strLastReleaseVersion = SvnSiteService.getLastRelease( site.getArtifactId() , site.getScmUrl(),request,locale );
        site.setLastReleaseVersion( strLastReleaseVersion );
        
        // To find next releases
        
        String strOriginVersion = getOriginVersion( strLastReleaseVersion, site.getVersion() );
        
        site.setNextReleaseVersion( Version.getReleaseVersion( strOriginVersion ));
        site.setNextSnapshotVersion( Version.getNextSnapshotVersion( strOriginVersion ) );
        site.setTargetVersions( Version.getNextReleaseVersions( strOriginVersion ));

        initComponents( site );
    }
    
    /**
     * Define which version between last released or current snapshot should be the origin
     * for next release versions. Ex of cases :<br>
     * last release : 3.2.1         current : 4.0.0-SNAPSHOT  --> current> <br>
     * last release : 3.2.1         current : 3.2.2-SNAPSHOT  --> last or current <br>
     * last release : missing       current : 1.0.0-SNAPSHOT  --> current <br>
     * last release : 3.2.1-RC-02   current : 3.2.1-SNAPSHOT  --> last <br>
     * 
     * @param strLastRelease The last release
     * @param strCurrentVersion The current release
     * @return The origin version
     */
    public static String getOriginVersion( String strLastRelease , String strCurrentVersion )
    {
        String strOriginVersion = strCurrentVersion;
        if( ( strLastRelease != null ) && Version.isCandidate( strLastRelease ))
        {
            strOriginVersion = strLastRelease;
        }
        return strOriginVersion;
    }
    
   
    
    /**
     * Initialize the component list for a given site
     * 
     * @param site
     *            The site
     */
    private static void initComponents( Site site )
    {
        for ( Dependency dependency : site.getCurrentDependencies( ) )
        {
            Component component = new Component( );
            
            component.setIsProject( isProjectComponent( site, dependency.getArtifactId( ) ) );
            component.setArtifactId( dependency.getArtifactId( ) );
            component.setGroupId( dependency.getGroupId( ) );
            component.setType( dependency.getType( ) );
            component.setCurrentVersion( dependency.getVersion( ) );
            site.addComponent( component );
        }
        
        ExecutorService executor = Executors.newFixedThreadPool(NB_POOL_REMOTE_INFORMATION);
        
        List<Future> futures = 
                new ArrayList<Future>( site.getCurrentDependencies( ).size( ));
              
           
        for ( Component component : site.getComponents( ) )
        {
            futures.add(executor.submit(new GetRemoteInformationsTask( component )));
        }
       
        
        //wait all futures stop before continue
        for (Future future : futures) {
            
            try
            {
                future.get();
            }
            catch( InterruptedException e )
            {
              AppLogService.error( e );
            }
            catch( ExecutionException e )
            {
                // TODO Auto-generated catch block
              AppLogService.error( e );
            }
              
          }
              
        executor.shutdown();     
            
        for ( Component component : site.getComponents( ) )
        {
           
            ComponentService.getService( ).updateRemoteInformations( component );
            defineTargetVersion( component );
            defineNextSnapshotVersion( component );
            component.setName( ReleaserUtils.getComponentName( component.getScmDeveloperConnection( ),component.getArtifactId( ) ) );
        }
           
            
          
        
    }
    
    

    /**
     * Define the target version for a given component : <br>
     * - current version for non project component <br>
     * - nex release for project component
     * 
     * @param component
     *            The component
     */
    private static void defineTargetVersion( Component component )
    {
        if ( component.isProject( ) && component.isSnapshotVersion( ) )
        {
            if( component.getLastAvailableVersion( )!=null && !component.getCurrentVersion( ).equals( component.getLastAvailableSnapshotVersion( )) || component.isTheme( ) )
            {
                component.setTargetVersion( component.getLastAvailableVersion( ) );  
            }
            else
            {
                component.setTargetVersions( Version.getNextReleaseVersions( component.getCurrentVersion() ));
                String strTargetVersion = Version.getReleaseVersion( component.getCurrentVersion() );
                component.setTargetVersion( strTargetVersion );
            }
        }
        else
        {
            component.setTargetVersion( component.getCurrentVersion( ) );
        }
    }

    /**
     * Define the next snapshot version for a given component
     * 
     * @param component
     *            The component
     */
    private static void defineNextSnapshotVersion( Component component )
    {
        String strNextSnapshotVersion = Version.NOT_AVAILABLE;
        if(  !component.getCurrentVersion( ).equals( component.getLastAvailableSnapshotVersion( )) || component.isTheme( ))
        {
            component.setNextSnapshotVersion( component.getLastAvailableSnapshotVersion( ) ); 
        }
        else
        {
            try
            {
                Version version = Version.parse( component.getTargetVersion( ) );
                boolean bSnapshot = true;
                strNextSnapshotVersion = version.nextPatch( bSnapshot ).toString( );
            }
            catch( VersionParsingException ex )
            {
                AppLogService.error( "Error parsing version for component " + component.getArtifactId( ) + " : " + ex.getMessage( ), ex );
    
            }
            component.setNextSnapshotVersion( strNextSnapshotVersion );
        }

    }

    private static boolean isProjectComponent( Site site, String strArtifactId )
    {
        return new Boolean(DatastoreService.getDataValue( getComponetIsProjectDataKey( site, strArtifactId ), Boolean.FALSE.toString( ) ));
    }
    
    public  static void updateComponentAsProjectStatus( Site site, String strArtifactId ,Boolean bIsProject)
    {
       DatastoreService.setDataValue( getComponetIsProjectDataKey(  site, strArtifactId),bIsProject.toString( ));
       
    }
    
    public  static void removeComponentAsProjectBySite( int  nIdSite )
    {
        DatastoreService.removeDataByPrefix( getPrefixIsProjectDataKey( nIdSite ) );
       
    }
    
   private static String getComponetIsProjectDataKey(  Site site, String strArtifactId )
   {
       
       return getPrefixIsProjectDataKey( site.getId( ) ) +strArtifactId;
       
   }
   
   private static String getPrefixIsProjectDataKey(  int  nIdSite  )
   {
       
       return ConstanteUtils.CONSTANTE_COMPONENT_PROJECT_PREFIX+"_"+nIdSite+"_";
       
   }
    

    /**
     * Build release comments for a given site
     * 
     * @param site
     *            The site
     * @param locale
     *            The locale to use for comments
     */
    public static void buildComments( Site site, Locale locale )
    {
        for ( Component component : site.getComponents( ) )
        {
            component.resetComments( );
            buildReleaseComments( component, locale );
        }
    }

    /**
     * Build release comments for a given component
     * 
     * @param component
     *            The component
     * @param locale
     *            The locale to use for comments
     */
    private static void buildReleaseComments( Component component, Locale locale )
    {
        
        if ( !component.isProject( ) )
        {
            if ( Version.isSnapshot( component.getTargetVersion( ) ) )
            {
                String strComment = I18nService.getLocalizedString( MESSAGE_AVOID_SNAPSHOT, locale );
                component.addReleaseComment( strComment );
            }
            else if (component.getLastAvailableVersion( )!=null && !component.getTargetVersion( ).equals( component.getLastAvailableVersion( )) )
            {
                String [ ] arguments = {
                        component.getLastAvailableVersion( )
                    };
                 String strComment = I18nService.getLocalizedString( MESSAGE_MORE_RECENT_VERSION_AVAILABLE, arguments, locale );
                  
                component.addReleaseComment( strComment );
            }
        }
        else
        {
            if(component.isSnapshotVersion( ))
            {
                if(  !component.getCurrentVersion( ).equals( component.getLastAvailableSnapshotVersion( )) )
                {
                    
                        String [ ] arguments = {
                            component.getLastAvailableVersion( )
                        };
                        String strComment = I18nService.getLocalizedString( MESSAGE_UPGRADE_SELECTED, arguments, locale );
                        component.addReleaseComment( strComment );
                }
                else if(  !component.shouldBeReleased( ) && !component.isDowngrade( ) )
                {
                    
                        String [ ] arguments = {
                            component.getLastAvailableVersion( )
                        };
                        String strComment = I18nService.getLocalizedString( MESSAGE_AN_RELEASE_VERSION_ALREADY_EXIST, arguments, locale );
                        component.addReleaseComment( strComment );
                }
           
            else if(component.shouldBeReleased( ))
            {
            
                String strComment = I18nService.getLocalizedString( MESSAGE_TO_BE_RELEASED, locale );
                component.addReleaseComment( strComment );
              }
            }
            else if ( !component.getCurrentVersion( ).equals( component.getLastAvailableVersion( )) )
            {
                String [ ] arguments = {
                        component.getLastAvailableVersion( )
                    };
                 String strComment = I18nService.getLocalizedString( MESSAGE_MORE_RECENT_VERSION_AVAILABLE, arguments, locale );
                  
                component.addReleaseComment( strComment );
            }
        }
    }

    
   

    public static void upgradeComponent( Site site, String strArtifactId )
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId ) )
            {
                component.setTargetVersion( component.getLastAvailableVersion( ) );
                component.setUpgrade( true );
            }
        }
    }
    
    
    public static void cancelUpgradeComponent( Site site, String strArtifactId )
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId ) )
            {
           
                component.setTargetVersion(  component.getCurrentVersion( ) );
                component.setUpgrade( false );
            }
        }
    }
    public static void downgradeComponent( Site site, String strArtifactId )
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId ) && component.isSnapshotVersion( ))
            {
                component.setTargetVersion( component.getLastAvailableVersion( ) );
                component.setNextSnapshotVersion( component.getLastAvailableSnapshotVersion( ) );
                component.setDowngrade( true );
            }
        }
    }
    
    public static void cancelDowngradeComponent( Site site, String strArtifactId )
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId ) && component.isSnapshotVersion( ))
            {
                component.setDowngrade( false );
                defineTargetVersion( component );
                defineNextSnapshotVersion( component );
            }
        }
    }
    
    public static int releaseComponent( Site site, String strArtifactId,Locale locale,AdminUser user,HttpServletRequest request)
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId )  && component.shouldBeReleased( ))
            {
                //Release component
               return  ComponentService.getService( ).release( component, locale,user,request );
                
            }
        }
        return ConstanteUtils.CONSTANTE_ID_NULL;
    }
    
    
    public static Map<String, Integer> releaseSite( Site site,Locale locale,AdminUser user,HttpServletRequest request)
    {
        Map<String, Integer> mapResultContext=new HashMap<String, Integer>();
        
        
        Integer nIdWfContext;
        //Release all snapshot compnent
        for ( Component component : site.getComponents( ) )
        {
            if ( component.isProject( ) && component.shouldBeReleased( )&& !component.isTheme( ))
            {
                component.setErrorLastRelease( false );
                nIdWfContext=ComponentService.getService( ).release( component, locale,user,request );
                mapResultContext.put( component.getArtifactId( ), nIdWfContext );
               
            }
        }
        
        
       
        WorkflowReleaseContext context=new WorkflowReleaseContext( );
        context.setSite( site );
        context.setReleaserUser( ReleaserUtils.getReleaserUser( request, locale ) );
       
        int nIdWorkflow=WorkflowReleaseContextService.getService( ).getIdWorkflow( context );
        WorkflowReleaseContextService.getService( ).addWorkflowReleaseContext( context );
        //start
        WorkflowReleaseContextService.getService( ).startWorkflowReleaseContext( context, nIdWorkflow, locale, request, user );
        //Add wf site context
        mapResultContext.put( site.getArtifactId( ), context.getId( ) );
        
         return mapResultContext;
        
       
        
        
    }
    
    
    

    /**
     * Add or Remove a component from the project's components list
     * 
     * @param site
     *            The site
     * @param strArtifactId
     *            The component artifact id
     */
    public static void toggleProjectComponent( Site site, String strArtifactId )
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId ) )
            {
                component.setIsProject( !component.isProject( ) );
                updateComponentAsProjectStatus( site, strArtifactId, component.isProject( ) );
                
                if(component.isProject( ))
                {
                    try
                    {
                        ComponentService.getService( ).setRemoteInformations( component, false  );
                    }
                    catch( HttpAccessException | IOException e )
                    {
                      AppLogService.error( e );
                    }
                    ComponentService.getService( ).updateRemoteInformations( component );
                    defineTargetVersion( component );
                    defineNextSnapshotVersion( component );
                    component.setName( ReleaserUtils.getComponentName( component.getScmDeveloperConnection( ) ,component.getArtifactId( )) );
                }
                
            }
        }
    }

    /**
     * Change the next release version
     * 
     * @param site
     *            The site
     * @param strArtifactId
     *            The component artifact id
     */
    public static void changeNextReleaseVersion( Site site, String strArtifactId )
    {
        for ( Component component : site.getComponents( ) )
        {
            if ( component.getArtifactId( ).equals( strArtifactId ) )
            {
                ComponentService.getService( ).changeNextReleaseVersion( component );
            }
        }
    }

    /**
     * Change the next release version
     * 
     * @param site
     *            The site
     */
    public static void changeNextReleaseVersion( Site site )
    {
        List<String> listTargetVersions = site.getTargetVersions();
        int nNewIndex = (site.getTargetVersionIndex() + 1) % listTargetVersions.size();
        String strTargetVersion = listTargetVersions.get( nNewIndex );
        site.setNextReleaseVersion( strTargetVersion );
        site.setTargetVersionIndex( nNewIndex );
        site.setNextSnapshotVersion( Version.getNextSnapshotVersion( strTargetVersion ));
    }

    
    
    /**
     * Generate the pom.xml file for a given site
     * 
     * @param site
     *            The site
     * @return The pom.xml content
     */
    public String generateTargetPOM( Site site )
    {
        throw new UnsupportedOperationException( "Not supported yet." ); // To change body of generated methods, choose Tools | Templates.
    }
    

}
