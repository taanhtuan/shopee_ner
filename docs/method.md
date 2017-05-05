Proposed method
===============

[0] __Data__
------------

### [0.1] Given data structure

A given training data contains following fields:

- `product_name` (or title) is a text describe information about product, including terms and brand information
- `core_terms` is labels for each sample in training data set
- `brand` contains list of brands for each sample in training data set
- `stop_words` is predefined a list of words should be removed in `product_name` of each sample
- Optional fields: `itemid`, `weekly_orders`, `main_cat`; but __not all of training datasets contain these fields__.
                   E.g. dataset 40

**To make sure the training datasets are consistent, I only used the first 4 field described above**. The optional fields
are nice to add as optional features, where they are support classifying more strictly in case of we got ambiguous 
multiple results in predicting.

Beside, feature likes unique `itemid` should not be taken into account.


### [0.2] Suggest adding fields

- `price` or `range of prices` should be added. E.g. given title `case of an phone`, we can extract both named entities
  `case` and `phone`, but by given `price` or (then transform to) `range of prices`, we can eliminate `phone` out of
  predicted result
- `time released / crawled` time of product will affect directly to frequency of appearance and price, will be useful
  to group similar patterns
- `provider` a provider has a tend to provide similar products
- `category` of a product, if it exists

And many...



[1] __Tasks and approach__
--------------------------

### [1.1] Tasks

There are 3 requirements:

1. Extract named entities (or core terms) out of `product_name`
2. Extract brands
3. ~~Extract major descriptive terms~~

*Note: the third requirement is not defined in document and given training dataset.*


### [1.2] Approach

The problem is kind of multi-label classification challenge where the model must predict multiple outcomes from given
set of features. There is 2 challenges:

1. Based on set of tokens (extracted from given text), find patterns of combinations of feature values in other to
recognize classes (`core terms`) can be related.
2. Discover unknown classes which are not existed in training datasets.

For the first challenge can be addressed to methods based on tree, regression embeded OnevsRest strategy, or both
classification and regression (k-NN  [3]) method, etc. I supported both Random Forest (based on Decision Trees which is
already integrated in Spark ML) and / or k-NN to solve the first problem.

However, the first solution only works well on sample observed in traning datasets. That means if new patterns or
classes are found in given test sample which did not exist in training data, it will be impossible to do predict. 
That is root problem to be solved for the second challenge.

Besides, `product_name` defined in section (0) is usually short and be written informally. That is the morphology of
formal sentences can not be used to predict the main subjective object (might be a named entity). So, we can not use
the rule base or NLP parser to accomplish extracting named entities. To solve that, I applied POS Tagger built for tweet
(similar kinds, which is short and unstructure textual content) in others to regonize 'noun', 'verb', ... and
disambiguate tokens.

The combination of solutions for challenges 1 and 2 will generate set of (candidate) named entities in given title. And
base on that, we map it/them to brands (mappings are defined in training dataset) if they are appeared in title.

The detailed information of training steps will be described in section (2), (3) and (4).



[2] __Feature extraction & selection__
--------------------------------------

In order to extract feature vectors for training process, the transformation follows the pipeline:


### [2.1] Clean and normalize raw data

* All field of data will be converted to lower case
* `core_terms` and `brand` will be split by `,` and store as array of string
* `core_terms` will be used as label, so I separated array of labels to multiple samples (rows), each row will contain
  similar `product_name` likes it before splitting
* `product_name` will be cleaned special character by space " ", i.e. "/", "@", "$", etc.
* `product_name` will be tokenized and remove common stop words by default vocabulary of Spark ML
  (refer to TokenRemover)
* set of tokens of `product_name` will be subtracted against predefined `stop_words` again to make sure we ignore
  as much as possible noise in our data


### [2.2] Transform data to vector of features

* Tokens extracted from previous step is single word. To compromise with a phrase, I extracted 2-grams and 3-grams,
  then merge them with original tokens before transforming to numeric vector
* The common way to convert word vector to fix numeric vector is applying count vector. I counted number of tokens in
  a sample than transform it to globally vector (where set of unique tokens will be treat as Bag of Words)


### [2.3] Reduce number of dimensions of vectors

* Because the vector frequency of words will be sparse, and the order is not concerned (informal written). So, I apply
  PCA to reduce size of dimensions and transform set of vectors to dense matrix before training.


### Note:

Solution at (2.2) is a technique in Information Retrieval, I also tried to apply HashingTF (based on TF-IDF) in other to
highlight meaningful words even it is rare word. However, the accuracy is not improved, the reason may be the named
entities tend to be used with common words. As the result, in this case, I think a binary vector to let machine know
which words come with a class is good enough.



[3] __Learning process__
------------------------

Once I got feature of feature vectors in numeric by above steps in section (2), I need to index labels (`core_terms`) to
numeric before feeding into a specific methods. The main approach is using Random Forest to classify based on Tree rules
and voting mechanism.

The train data and test data will be split by ratio 70% and 30% randomly. Then, it will be fed into Cross
Validation (CV) with 10 folds (you can change by yourself). The parameter grid of Random Forest will be defined for CV
in order to pick best models based on randomly derived data.

### Notes:

1. There is a lot different ways to do model selection, CV approach is not the best solution in this case but it
   is picked due to time constrain of project
2. The vocabulary mapping between index of named entities is stored to resolve named entities in predicting process
3. The mapping between named entities and brands is also pruned and stored for predicting process



[4] __Predicting process__
--------------------------

Make sure the model is ready after training in section (3). I repeated step 2 and 3 in section (2) to generate token
vector from raw data before feeding to model. The predicted named entities will be resolved by vocabulary defined by
note 2 of section (3).

To deal with second challenge defined in section (1.2), I do predict process once again by POS tagger. The POS tagger
[4] is built for special case of tweet [5, 6], which is fit with characteristics of provided data (short and informal).

The result will be merged, with higher priority of first result.

The final named entities will be used to scan mapped brands (stored at note 3rd in section (3)). And found candidate
brands will be double checked with vector of tokens. If both conditions are satisfied, candidate brands will be picked.

### Question:

Can statistical model be applied to figure out brands from vector of tokens and found named entities?

Yes, it can. But, brands can be predicted even they do not exist in token vector. Besides, both core terms and brands
are named entities. However, we do not have a clear concept or definition to distinguish them (Ontology is a
suggestion). So, it's very risky if I apply statistical approach to predict brand for this case when features are
not well declared.



[5] __References__
------------------

[1] [Multi-label classification](https://en.wikipedia.org/wiki/Multi-label_classification), Wiki

[2] [Multiclass classification](https://en.wikipedia.org/wiki/Multiclass_classification), Wiki

[3] Liu, Ting, Charles Rosenberg, and Henry Rowley. "Clustering billions of images with large scale nearest neighbor search." Applications of Computer Vision, 2007. WACV'07. IEEE Workshop on. IEEE, 2007.

[4] Kristina Toutanova, Dan Klein, Christopher Manning, and Yoram Singer. 2003. Feature-Rich Part-of-Speech Tagging with a Cyclic Dependency Network. In Proceedings of HLT-NAACL 2003, pp. 252-259.

[5] [Stanford Log-linear Part-Of-Speech Tagger](https://nlp.stanford.edu/software/tagger.shtml)

[6] [GATE Twitter Part-Of-Speech Tagger](https://gate.ac.uk/wiki/twitter-postagger.html)
