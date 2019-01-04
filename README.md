# Artifactory-View

Tiny Java/Swing desktop app that queries repository data from an Artifactory server and renders storage requirements as a treemap.
Why Java and not some funky Javascript webapp, you ask? Because Artifactory as of today has no way to retrieve the size of an entire folder so I have to recursively query all children to determine the folder size. Since this means one REST API call for every folder and every artifact in a repository I needed some performant parallel processing to make the runtime bearable, not exactly Javascript's strength. My initial attempt was a quick hack in Python but it was waaaaaay too slow.. 

![Screenshot](https://raw.githubusercontent.com/toby1984/artifactory-view/master/screenshot.png)
