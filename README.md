## Screen Operator
### Operates the screen with AI
This Android app operates the screen with commands from vision LLMs

• Like Computer use and Operator but for Android

• Can also control the Browser like Project Mariner and Browser use

### How to get it from Google Play


I must all „Tester“ E-Mails enter in the list of the Google Play Console, because „Every developer has to have his app tested extensively to publish it regular“. Google determines the time frame for all this. Unfortunately, this takes about a month.

Here can you enter an E-Mail. [https://docs.google.com/forms/d/1wQiKmP9R2PTmZQe\_1ZAp3KCv9M8\_d9nlqoLsLot2M-I/edit](https://docs.google.com/forms/d/1wQiKmP9R2PTmZQe_1ZAp3KCv9M8_d9nlqoLsLot2M-I/edit) You get a notification when I enter it in the console. You can then download it regularly from the Play Store.

### Video

https://m.youtube.com/watch?v=o095RSFXJuc

### Help with development

Current development step:

Fix a bug that prevents some apps from being launched by Screen Operator (branch Better_text).

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
  "keywords": ["Screen", "Operator", "vision", "LLMs", "control", "AI", "Agents"]
}
</script>
