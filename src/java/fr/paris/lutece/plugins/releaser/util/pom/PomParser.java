/*
 * Copyright (c) 2002-2015, Mairie de Paris
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
package fr.paris.lutece.plugins.releaser.util.pom;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.xml.sax.InputSource;

import fr.paris.lutece.plugins.releaser.business.jaxb.maven.Model;
import fr.paris.lutece.plugins.releaser.business.Component;
import fr.paris.lutece.plugins.releaser.business.Dependency;
import fr.paris.lutece.plugins.releaser.business.Site;
import fr.paris.lutece.portal.service.util.AppLogService;

/**
 * PomParser
 */
public class PomParser
{
    // Tags



    private ArrayList<Dependency> _listDependencies = new ArrayList<Dependency>( );

    public List<Dependency> getDependencies( )
    {
        return _listDependencies;
    }

    public void parse( Site site, String strPOM )
    {
        try
        {
            InputSource isPOM = new InputSource( new StringReader( strPOM ) );
            Model model = unmarshal( Model.class, isPOM );
         
            filledSite( site, model );
         
             fr.paris.lutece.plugins.releaser.business.jaxb.maven.Model.Dependencies dependencies=model.getDependencies( );
             
            if(dependencies!=null)
            {
                for (  fr.paris.lutece.plugins.releaser.business.jaxb.maven.Dependency jaxDependency: dependencies.getDependency( ))
                {
                      filledDependency( site, jaxDependency );
                    
                }
            }
        }
        catch ( JAXBException e )
        {
           AppLogService.error( e );
        }
    }
    
    public void parse( Component component , String strPOM )
    {
        
        try
        {
            InputSource isPOM = new InputSource( new StringReader( strPOM ) );
            Model model = unmarshal( Model.class, isPOM );
            component.setArtifactId( model.getArtifactId( ) );
            component.setGroupId( model.getGroupId( ) );
            component.setCurrentVersion( model.getVersion( ) );
            if(model.getScm() !=null)
            {
                component.setScmDeveloperConnection( model.getScm( ).getDeveloperConnection( ) );
                
            }
        
        }
        catch ( JAXBException e )
        {
           AppLogService.error( e );
        }
    

    }
    public void parse( Component component , InputStream inputStream )
    {
        
        try
        {
             Model model = PomUpdater.unmarshal( Model.class, inputStream );
            component.setArtifactId( model.getArtifactId( ) );
            component.setGroupId( model.getGroupId( ) );
            component.setCurrentVersion( model.getVersion( ) );
            if(model.getScm() !=null)
            {
                component.setScmDeveloperConnection( model.getScm( ).getDeveloperConnection( ) );
                
            }
        
        }
        catch ( JAXBException e )
        {
           AppLogService.error( e );
        }
        finally
        {
            
            try
            {
                inputStream.close( );
            }
            catch( IOException e )
            {
               AppLogService.error( e );
            }
        }
    

    }

    private void filledSite( Site site, Model model )
    {
        site.setArtifactId(model.getArtifactId( ) );
        site.setGroupId( model.getGroupId( ) );
        site.setVersion( model.getVersion( ) );
    }

    private void filledDependency( Site site,fr.paris.lutece.plugins.releaser.business.jaxb.maven.Dependency  jaxDependency )
    {
        Dependency dep = new Dependency( );
        dep.setArtifactId( jaxDependency.getArtifactId( ) );
        dep.setVersion( jaxDependency.getVersion( ));
        dep.setGroupId( jaxDependency.getGroupId( ) );
        dep.setType( jaxDependency.getType( ) );
        
        site.addCurrentDependency( dep );
    }
    
    

    public static <T> T unmarshal( Class<T> docClass, InputSource inputSource )
        throws JAXBException
    {
        String packageName = docClass.getPackage(  ).getName(  );
        JAXBContext jc = JAXBContext.newInstance( packageName );
        Unmarshaller u = jc.createUnmarshaller(  );
        JAXBElement<T> doc = (JAXBElement<T>) u.unmarshal( inputSource );

        return doc.getValue(  );
    }

   

}
