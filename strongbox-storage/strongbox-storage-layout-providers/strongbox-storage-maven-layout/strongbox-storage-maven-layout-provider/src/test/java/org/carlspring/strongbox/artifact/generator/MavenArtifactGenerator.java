package org.carlspring.strongbox.artifact.generator;

import org.carlspring.commons.encryption.EncryptionAlgorithmsEnum;
import org.carlspring.commons.io.MultipleDigestInputStream;
import org.carlspring.commons.io.MultipleDigestOutputStream;
import org.carlspring.commons.io.RandomInputStream;
import org.carlspring.strongbox.artifact.MavenArtifactUtils;
import org.carlspring.strongbox.testing.artifact.MavenArtifactTestUtils;

import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author mtodorov
 */
public class MavenArtifactGenerator implements ArtifactGenerator
{

    private static final Logger logger = LoggerFactory.getLogger(MavenArtifactGenerator.class);

    public static final String PACKAGING_JAR = "jar";

    protected Path basedir;

    public MavenArtifactGenerator()
    {
    }

    public MavenArtifactGenerator(String basedir)
    {
        this.basedir = Paths.get(basedir);
    }

    public MavenArtifactGenerator(File basedir)
    {
        this.basedir = basedir.toPath();
    }

    public MavenArtifactGenerator(Path basedir)
    {
        this.basedir = basedir;
    }

    @Override
    public Path generateArtifact(String id,
                                 String version,
                                 int size)
        throws IOException
    {
        Artifact artifact = MavenArtifactTestUtils.getArtifactFromGAVTC(String.format("%s:%s", id, version));

        return generateArtifact(artifact);
    }

    @Override
    public Path generateArtifact(URI uri,
                                 int size)
        throws IOException
    {
        Artifact artifact = MavenArtifactUtils.convertPathToArtifact(uri.toString());

        return generateArtifact(artifact);
    }

    private Path generateArtifact(Artifact artifact)
        throws IOException
    {
        try
        {
            generate(artifact);
        }
        catch (NoSuchAlgorithmException|XmlPullParserException e)
        {
            throw new IOException(e);
        }

        return basedir.resolve(MavenArtifactUtils.convertArtifactToPath(artifact));
    }

    public void generate(String ga, String packaging, String... versions)
            throws IOException,
                   XmlPullParserException,
                   NoSuchAlgorithmException
    {
        if (packaging == null)
        {
            packaging = PACKAGING_JAR;
        }

        for (String version : versions)
        {
            Artifact artifact = MavenArtifactTestUtils.getArtifactFromGAVTC(ga + ":" + version);
            artifact.setFile(new File(getBasedir() + "/" + MavenArtifactUtils.convertArtifactToPath(artifact)));

            generate(artifact, packaging);
        }
    }

    public void generate(String ga, String... versions)
            throws IOException,
                   XmlPullParserException,
                   NoSuchAlgorithmException
    {
        for (String version : versions)
        {
            Artifact artifact = MavenArtifactTestUtils.getArtifactFromGAVTC(ga + ":" + version);
            artifact.setFile(new File(getBasedir() + "/" + MavenArtifactUtils.convertArtifactToPath(artifact)));

            generate(artifact);
        }
    }

    public void generate(Artifact artifact)
            throws IOException,
                   XmlPullParserException,
                   NoSuchAlgorithmException
    {
        generatePom(artifact, PACKAGING_JAR);
        createArchive(artifact);
    }

    public void generate(Artifact artifact, String packaging)
            throws IOException,
                   XmlPullParserException,
                   NoSuchAlgorithmException
    {
        generatePom(artifact, packaging);
        createArchive(artifact);
    }

    public void createArchive(Artifact artifact)
            throws NoSuchAlgorithmException,
                   IOException
    {
        File artifactFile = basedir.resolve(MavenArtifactUtils.convertArtifactToPath(artifact)).toFile();

        // Make sure the artifact's parent directory exists before writing the model.
        //noinspection ResultOfMethodCallIgnored
        artifactFile.getParentFile().mkdirs();

        try(ZipOutputStream zos = new ZipOutputStream(newOutputStream(artifactFile)))
        {
            createMavenPropertiesFile(artifact, zos);
            addMavenPomFile(artifact, zos);
            createRandomSizeFile(zos);

            zos.flush();
        }
        generateChecksumsForArtifact(artifactFile);
    }

    protected OutputStream newOutputStream(File artifactFile)
        throws IOException
    {
        return new FileOutputStream(artifactFile);
    }

    public void createMetadata(Metadata metadata, String metadataPath)
            throws NoSuchAlgorithmException, IOException
    {
        File metadataFile = null;

        try
        {
            metadataFile = basedir.resolve(metadataPath).toFile();

            if (metadataFile.exists())
            {
                metadataFile.delete();
            }

            // Make sure the artifact's parent directory exists before writing
            // the model.
            // noinspection ResultOfMethodCallIgnored
            metadataFile.getParentFile().mkdirs();

            try (OutputStream os = new MultipleDigestOutputStream(metadataFile, newOutputStream(metadataFile)))
            {
                Writer writer = WriterFactory.newXmlWriter(os);
                MetadataXpp3Writer mappingWriter = new MetadataXpp3Writer();
                mappingWriter.write(writer, metadata);

                os.flush();
            }
        }
        finally
        {
            generateChecksumsForArtifact(metadataFile);
        }
    }

    private void addMavenPomFile(Artifact artifact, ZipOutputStream zos) throws IOException
    {
        final Artifact pomArtifact = MavenArtifactTestUtils.getPOMArtifact(artifact);
        File pomFile = basedir.resolve(MavenArtifactUtils.convertArtifactToPath(pomArtifact)).toFile();

        ZipEntry ze = new ZipEntry("META-INF/maven/" +
                                   artifact.getGroupId() + "/" +
                                   artifact.getArtifactId() + "/" +
                                   "pom.xml");
        zos.putNextEntry(ze);

        try (FileInputStream fis = new FileInputStream(pomFile))
        {

            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) > 0)
            {
                zos.write(buffer, 0, len);
            }
        }
        finally
        {
            zos.closeEntry();
        }
    }

    private void createMavenPropertiesFile(Artifact artifact, ZipOutputStream zos)
            throws IOException
    {
        ZipEntry ze = new ZipEntry("META-INF/maven/" +
                                   artifact.getGroupId() + "/" +
                                   artifact.getArtifactId() + "/" +
                                   "pom.properties");
        zos.putNextEntry(ze);

        Properties properties = new Properties();
        properties.setProperty("groupId", artifact.getGroupId());
        properties.setProperty("artifactId", artifact.getArtifactId());
        properties.setProperty("version", artifact.getVersion());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        properties.store(baos, null);

        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());

        byte[] buffer = new byte[4096];
        int len;
        while ((len = bais.read(buffer)) > 0)
        {
            zos.write(buffer, 0, len);
        }

        bais.close();
        zos.closeEntry();
    }

    private void createRandomSizeFile(ZipOutputStream zos)
            throws IOException
    {
        ZipEntry ze = new ZipEntry("random-size-file");
        zos.putNextEntry(ze);

        RandomInputStream ris = new RandomInputStream(true, 1000000);

        byte[] buffer = new byte[4096];
        int len;
        while ((len = ris.read(buffer)) > 0)
        {
            zos.write(buffer, 0, len);
        }

        ris.close();
        zos.closeEntry();
    }

    public void generatePom(Artifact artifact, String packaging)
            throws IOException,
                   NoSuchAlgorithmException
    {
        final Artifact pomArtifact = MavenArtifactTestUtils.getPOMArtifact(artifact);
        File pomFile = basedir.resolve(MavenArtifactUtils.convertArtifactToPath(pomArtifact)).toFile();

        // Make sure the artifact's parent directory exists before writing the model.
        //noinspection ResultOfMethodCallIgnored
        pomFile.getParentFile().mkdirs();

        Model model = new Model();
        model.setGroupId(artifact.getGroupId());
        model.setArtifactId(artifact.getArtifactId());
        model.setVersion(artifact.getVersion());
        model.setPackaging(packaging);

        logger.debug("Generating pom file for {}...", artifact);

        try (OutputStreamWriter pomFileWriter = new OutputStreamWriter(newOutputStream(pomFile)))
        {
            MavenXpp3Writer xpp3Writer = new MavenXpp3Writer();
            xpp3Writer.write(pomFileWriter, model);
        }

        generateChecksumsForArtifact(pomFile);
    }

    private void generateChecksumsForArtifact(File artifactFile)
            throws NoSuchAlgorithmException, IOException
    {
        try (InputStream is = new FileInputStream(artifactFile);
             MultipleDigestInputStream mdis = new MultipleDigestInputStream(is))
        {
            int size = 4096;
            byte[] bytes = new byte[size];

            //noinspection StatementWithEmptyBody
            while (mdis.read(bytes, 0, size) != -1) ;

            mdis.close();

            String md5 = mdis.getMessageDigestAsHexadecimalString(EncryptionAlgorithmsEnum.MD5.getAlgorithm());
            String sha1 = mdis.getMessageDigestAsHexadecimalString(EncryptionAlgorithmsEnum.SHA1.getAlgorithm());

            Path artifactPath = artifactFile.toPath();

            Path checksumPath = artifactPath.resolveSibling(artifactPath.getFileName() + EncryptionAlgorithmsEnum.MD5.getExtension());
            try (OutputStream os = newOutputStream(checksumPath.toFile()))
            {
                IOUtils.write(md5, os, Charset.forName("UTF-8"));
                os.flush();
            }

            checksumPath = artifactPath.resolveSibling(artifactPath.getFileName() + EncryptionAlgorithmsEnum.SHA1.getExtension());
            try (OutputStream os = newOutputStream(checksumPath.toFile()))
            {
                IOUtils.write(sha1, os, Charset.forName("UTF-8"));
                os.flush();
            }
        }
    }

    public String getBasedir()
    {
        return basedir.toAbsolutePath().toString();
    }

    public Path getBasedirPath()
    {
        return basedir;
    }
}
