## Screen Operator
### Operates the screen with AI
This Android app operates the screen with commands from vision LLMs



#### • Like Computer use and Operator but rather Smartphone use for Android

#### • Can also control the Browser like Project Mariner and Browser use

<img src="https://github.com/Android-PowerUser/Screen_Operator/blob/main/Screenshot_20250526-192615_Screen%20Operator.png" alt="" width="141"/> <img src="https://github.com/Android-PowerUser/Screen_Operator/blob/main/Screenshot_20250521-095334_Screen%20Operator.png" alt="" width="141"/>

### How to get it from Google Play
<br/>

Unfortunately, I need 12 testers for 14 days to publish the app on the Play Store and Google doesn't even update the index of this page. 💩

For the Play Store link to work you must first join the [Google Group](https://groups.google.com/g/Screen_Operator) You can then download it regularly from the [Play Store](https://play.google.com/store/apps/details?id=io.github.android_poweruser).

### Video
[First attempt ever is recorded](https://m.youtube.com/watch?v=o095RSFXJuc)

<br/>

#### Note

If you in your Google account identified as under 18, you need an adult account because Google is (unreasonably) denying you the API key.

Android 11-12.1 doesn't work because of file permission problems with the screenshots path. Participate in the Project (branch better_text or Android_11_read_media_images). Please test yourself if Android 10- works and let me know the result.

##### Help with development

Current development step:

Fix a bug that prevents some apps from being launched by Screen Operator (branch Better_text). The current workaround is to go from the home screen.

Correct implementation of MediaProjection (Create screenshots for Gemini). Almost finished code in the screenshot branch. The current workaround is to trigger a screenshot via the accessibility service. However, this leads to the thumbnail.


###### For the Google crawler
<script type="application/ld+json">
{
  "@context": "https://schema.org",
  "@type": "AndroidApp",
  "name": "Screen Operator",
  "description": "Operates the screen with AI",
  "programmingLanguage": "Kotlin",
  "codeRepository": "https://github.com/Android-PowerUser/ScreenOperator",
  "author": {
    "@type": "Person",
    "name": "Android PowerUser"
  },
  "dateCreated": "2025-04-07",
  "keywords": ["Screen", "Operator", "vision", "LLMs", "control", "AI", "Agents", "AI Agents", "Computer Use", "Browser use", "Project Mariner", "Screen Operator", "vision LLM", "Smartphone use", "Android use"]
}
</script>
