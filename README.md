<meta name="google-site-verification" content="L0pGitxnZAFYOsci2bw63teKz2w7JcMxbQwowxUFEZw" />

## Screen Operator (In development stage)

This app is intended to operate the screen with AI.

### Waiting list
Get notified with an E-Mail. https://docs.google.com/forms/d/1wQiKmP9R2PTmZQe_1ZAp3KCv9M8_d9nlqoLsLot2M-I/edit

### Help with development

Current development step:

Fix a bug that caused that if you switch the LLM you must go back and in again (branch auto-change-LLM-attempt_2).

Fix a bug that prevents some apps from being launched by Screen Operator (branch open_apps_attempt_2).

Correct implementation of MediaProjection (Create screenshots for Gemini). Almost finished code in the screenshot branch. The current workaround is to trigger a screenshot via the accessibility service. However, this leads to the thumbnail.

#### Free API

Follow the instructions on Google AI Studio [setup page](https://makersuite.google.com/app/apikey) to obtain an API key.

```txt
apiKey=YOUR_API_KEY
```

##### Documentation

You can find the quick start documentation for the Android Generative AI API [here](https://ai.google.dev/tutorials/android_quickstart).
