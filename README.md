# ImageCacheLib_Android

#### Library overview

This library was based on a library of DisplayingBitmaps that can be DiskCache and MemoryCache. We improved the following things:

* Instead of using two DiskCache targets, use only one DiskCache

* Can specify whether to use DiskCache every acquisition of image

* You can initialize DiskCache on the way of using

#### How to use the library
##### 1. Initialize
It is mandatory to initialize ImageFetcher to the onCreate class of the class that inherits Application

Declare ImageCacheParams
> public ImageCacheParams (Context context, String diskCacheDirectoryName)
> diskCacheDirectoryName: The name of the folder where the acquired image is saved

Declare whether to use DiskCache
> public void setDiskCacheEnabled (boolean enabled)
> Regardless of whether DiskCache is used or not, MemoryCache initializes as MemoryCacheParams initializes

Declare ImageFetcher
> public ImageFetcher (Context context, ImageCache.ImageCacheParams cacheParams)
> cacheParams is null, do not use MemoryCache and DiskCache

Specify the image to display while acquiring the image
> public void setLoadingImage (int resId)

A case

```java
ImageCache.ImageCacheParams cacheParams = new ImageCache.ImageCacheParams (this, IMAGE_CACHE_DIR);
cacheParams.setDiskCacheEnabled (true);
mImageFetcher = new ImageFetcher (this, cacheParams);
mImageFetcher.setLoadingImage (R. drawable.empty_photo);
```

* As for the disk cache, in order not to display the old image, we recommend that you clear the cache data here

##### 2. Get ImageFetcher
Acquire ImageFetcher target for each Activity or Fragment

```java
ImageFetcher mImageFetcher = ((App) getApplication ()). GetImageFetcher ();
mImageFetcher.setListener (this);
```

##### 3. Retrieve the image
> public void load (String urlString, ImageView imageView, boolean diskCacheEnabled);

imageView: View the acquired image View target
diskCacheEnabled: Specify whether to store the image in DiskCache. Even if DiskCache target does not initialize yet at initialization stage, if it is true DiskCache target can be initialized from here

A case

```java
mImageFetcher.load (image_url, mImageView, true);
```

##### 4. Task is completed early, ignoring processing
> public void setExitTasksEarly (boolean exitTasksEarly)

existTaskEarly

* By default it is false

* If true, ignore the process to the task that is acquiring the image and complete each task ahead of time

**Best practice**

```java
@Override
protected void onResume () {
    super.onResume ();
    mImageFetcher.setExitTasksEarly (false);
}

@Override
protected void onPause () {
    super.onPause ();
    mImageFetcher.setExitTasksEarly (true);
}
```

##### 5. Cancel the task
> public boolean cancelWork (ImageView imageView)

Link ImageView to Step 3 Cancel the task of the background thread

A case

```java
mImageFetcher.cancleWork (mImageView);
```

##### 6. Clear cache data
> public void clearCache ()

Clear the data of MemoryCache and DiskCache. If DiskCache does not initialize yet, it can not clear the data cached by DiskCache before

A case

```java
mImageFetcher.clearCache ();
```

##### 7. Close the image cache
> public void closeCache ();

You can call with the onTerminate method of the class that inherits Application

A case

```java
@Override
public void onTerminate () {
    super.onTerminate ();
    mImageFetcher.closeCache ();
}
```
